/*
 * Native 崩溃捕获（信号处理器）。
 *
 * 为什么需要它：
 *   Java 的 Thread.setDefaultUncaughtExceptionHandler 只能抓 Java 层异常。
 *   本项目在 CX11 车机上的闪退实测是 native 层 SIGABRT（signal 6, si_code=-6 SI_TKILL），
 *   这类崩溃不触发任何 Java 回调，内存日志也随进程消失。
 *
 * 实测教训（v1 / v2 的 bug，已修）：
 *   1) v1 在信号处理器里调用 _Unwind_Backtrace 取调用栈 —— 结果**处理器自己挂掉**，
 *      文件只写到 "fault addr" 就断了，maps 和调用栈全丢。
 *      → 现在完全不用 libunwind，改为从 ucontext 取 PC/SP/LR，再 dump 栈内存。
 *   2) v1 把 maps 写在最后，一旦前面出错就永远拿不到 maps。
 *      → 现在 maps 先读进内存缓冲，解析后再输出结论。
 *   3) v2 原样 dump 96KB 的 /proc/self/maps —— 上传时被截断，
 *      远端只看到一堆地址，看不到「崩在哪个 .so」。
 *      → v3：在进程内直接把 pc / lr / 栈上返回地址换算成「模块 + 偏移」，
 *         只输出可执行模块表（去重），总量压到 ~10KB，保证完整上传。
 *   4) v3 新增：崩溃线程名读 /proc/self/task/<tid>/comm（v2 读的是 /proc/self/comm，
 *      永远是包名，看不出是 Binder 线程还是轮询线程）。
 *
 * 安全约束（重要）：
 *   信号处理器里只调用异步信号安全的函数：open / read / write / close / gettid / dup2。
 *   刻意**不使用** dladdr / malloc / printf / __android_log_print / _Unwind_* ——
 *   它们可能加锁，在「崩溃时正持有该锁」的场景下会死锁，把闪退变成卡死，反而更糟。
 *
 * ★ stderr 重定向：nativeInstall 时把 fd 2 指到 native_crash.txt.err。
 *   libc 的 assert / 堆破坏检测在 abort() **之前**会先把原因写到 stderr，
 *   应用进程默认 stderr 是 /dev/null，那些关键信息（如 "free(): invalid pointer"）
 *   本来全丢；重定向后下次启动就能回捞到。
 */
#include <jni.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <stdint.h>
#include <sys/ucontext.h>

#define PATH_MAX_LEN      512
#define MAPS_MAX_BYTES    (512 * 1024)
#define MAX_ENTRIES       4000
#define STACK_DUMP_WORDS  256
#define CALL_CHAIN_MAX    40
#define UNIQ_NAME_MAX     256

static char g_path[PATH_MAX_LEN];
static int g_installed = 0;

/* maps 读入内存后再解析（BSS，约 260KB，可接受） */
static char g_maps[MAPS_MAX_BYTES + 4096];

struct ent {
    uintptr_t start;
    uintptr_t end;
    const char* name;
    char exec;
};
static struct ent g_ent[MAX_ENTRIES];
static int g_n = 0;

static const int g_signals[] = { SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGTRAP, SIGSYS };
static const int g_signal_count = (int)(sizeof(g_signals) / sizeof(g_signals[0]));
static struct sigaction g_old_actions[sizeof(g_signals) / sizeof(g_signals[0])];

/* 从 ucontext 取崩溃线程的 PC / SP / LR */
#if defined(__aarch64__)
  #define UC_PC(uc) ((uintptr_t)((uc)->uc_mcontext.pc))
  #define UC_SP(uc) ((uintptr_t)((uc)->uc_mcontext.sp))
  #define UC_LR(uc) ((uintptr_t)((uc)->uc_mcontext.regs[30]))
#elif defined(__arm__)
  #define UC_PC(uc) ((uintptr_t)((uc)->uc_mcontext.arm_pc))
  #define UC_SP(uc) ((uintptr_t)((uc)->uc_mcontext.arm_sp))
  #define UC_LR(uc) ((uintptr_t)((uc)->uc_mcontext.arm_lr))
#else
  #define UC_PC(uc) ((uintptr_t)0)
  #define UC_SP(uc) ((uintptr_t)0)
  #define UC_LR(uc) ((uintptr_t)0)
#endif

/* ---------------- 异步信号安全的输出工具 ---------------- */

static void put_str(int fd, const char* s) {
    if (!s) return;
    size_t n = strlen(s);
    while (n > 0) {
        ssize_t w = write(fd, s, n);
        if (w <= 0) break;
        s += w;
        n -= (size_t)w;
    }
}

static void put_ll(int fd, long long v) {
    char tmp[24];
    int i = 0;
    unsigned long long u;
    if (v < 0) { put_str(fd, "-"); u = (unsigned long long)(-v); }
    else       { u = (unsigned long long)v; }
    if (u == 0) { put_str(fd, "0"); return; }
    while (u > 0 && i < 23) { tmp[i++] = (char)('0' + (int)(u % 10)); u /= 10; }
    while (i > 0) {
        char c = tmp[--i];
        ssize_t w = write(fd, &c, 1);
        if (w <= 0) break;
    }
}

static void put_hex(int fd, uintptr_t v) {
    static const char* H = "0123456789abcdef";
    char out[19];
    out[0] = '0'; out[1] = 'x';
    for (int i = 0; i < 16; i++) {
        out[2 + i] = H[(v >> ((15 - i) * 4)) & 0xF];
    }
    ssize_t w = write(fd, out, sizeof(out));
    (void)w;
}

static const char* sig_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV (段错误 / 非法内存访问)";
        case SIGABRT: return "SIGABRT (abort，常见于 C++ 异常/断言/堆破坏)";
        case SIGBUS:  return "SIGBUS (总线错误)";
        case SIGILL:  return "SIGILL (非法指令)";
        case SIGFPE:  return "SIGFPE (算术异常)";
        case SIGTRAP: return "SIGTRAP";
        case SIGSYS:  return "SIGSYS";
        default:      return "UNKNOWN";
    }
}

/* 十进制无符号数写进 buffer 末尾，返回写入长度 */
static int dec_to_buf(char* buf, unsigned long v) {
    char tmp[24];
    int i = 0;
    if (v == 0) { buf[0] = '0'; return 1; }
    while (v > 0 && i < 23) { tmp[i++] = (char)('0' + (int)(v % 10)); v /= 10; }
    int n = 0;
    while (i > 0) buf[n++] = tmp[--i];
    return n;
}

/* ---------------- maps 解析 ---------------- */

static uintptr_t hexval(const char* s, const char* e) {
    uintptr_t v = 0;
    while (s < e) {
        char c = *s++;
        int d;
        if (c >= '0' && c <= '9') d = c - '0';
        else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
        else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
        else break;
        v = (v << 4) | (uintptr_t)d;
    }
    return v;
}

static char* next_tok(char** pp) {
    char* p = *pp;
    while (*p == ' ') p++;
    if (*p == 0) return NULL;
    char* s = p;
    while (*p && *p != ' ') p++;
    if (*p) { *p = '\0'; p++; }
    *pp = p;
    return s;
}

static void parse_maps(void) {
    g_n = 0;
    char* p = g_maps;
    while (*p && g_n < MAX_ENTRIES) {
        char* eol = strchr(p, '\n');
        char* next;
        if (eol) { *eol = '\0'; next = eol + 1; } else { next = p + strlen(p); }

        char* cur = p;
        char* addr = next_tok(&cur);
        char* perms = next_tok(&cur);
        next_tok(&cur);  /* offset */
        next_tok(&cur);  /* dev */
        next_tok(&cur);  /* inode */
        char* name = cur;
        while (*name == ' ') name++;

        if (addr && perms) {
            char* dash = strchr(addr, '-');
            if (dash) {
                struct ent* e = &g_ent[g_n++];
                e->start = hexval(addr, dash);
                e->end   = hexval(dash + 1, addr + strlen(addr));
                e->name  = name;
                e->exec  = (perms[2] == 'x') ? 1 : 0;
            }
        }
        p = next;
    }
}

static struct ent* find_ent(uintptr_t a) {
    for (int i = 0; i < g_n; i++) {
        if (a >= g_ent[i].start && a < g_ent[i].end) return &g_ent[i];
    }
    return NULL;
}

static const char* base_name(const char* s) {
    const char* last = s;
    for (const char* p = s; *p; p++) if (*p == '/') last = p + 1;
    return last;
}

/* 打印 "0x地址  模块+0x偏移" */
static void put_resolve(int fd, uintptr_t a) {
    put_hex(fd, a);
    struct ent* e = find_ent(a);
    if (e) {
        if (e->name && e->name[0]) {
            put_str(fd, "  ");
            put_str(fd, e->name);
            put_str(fd, "+0x");
            put_hex(fd, a - e->start);
        } else {
            put_str(fd, "  [匿名/无名映射]");
        }
    } else {
        put_str(fd, "  (不在任何映射内)");
    }
}

static void read_maps(void) {
    int m = open("/proc/self/maps", O_RDONLY);
    if (m < 0) { g_maps[0] = '\0'; return; }
    size_t total = 0;
    for (;;) {
        if (total + 4096 > sizeof(g_maps) - 1) break;
        ssize_t r = read(m, g_maps + total, 4096);
        if (r <= 0) break;
        total += (size_t)r;
    }
    g_maps[total] = '\0';
    close(m);
}

/* ---------------- 各段输出 ---------------- */

/* 崩溃线程名：必须读 /proc/self/task/<tid>/comm，/proc/self/comm 永远是包名 */
static void put_thread_name(int fd) {
    put_str(fd, "tid         : ");
    unsigned long tid = (unsigned long)gettid();
    put_ll(fd, (long long)tid);

    char path[64];
    const char* prefix = "/proc/self/task/";
    size_t o = 0;
    while (prefix[o]) { path[o] = prefix[o]; o++; }
    o += (size_t)dec_to_buf(path + o, tid);
    const char* suffix = "/comm";
    for (size_t i = 0; suffix[i]; i++) path[o + i] = suffix[i];
    path[o + 5] = '\0';

    int f = open(path, O_RDONLY);
    if (f >= 0) {
        char buf[64];
        ssize_t r = read(f, buf, sizeof(buf) - 1);
        if (r > 0) {
            buf[r] = '\0';
            for (ssize_t i = 0; i < r; i++) if (buf[i] == '\n') { buf[i] = '\0'; break; }
            put_str(fd, "  comm=");
            put_str(fd, buf);
        }
        close(f);
    } else {
        put_str(fd, "  comm=(未知)");
    }
    put_str(fd, "\n");
}

/* 可执行模块表：按名字去重，段地址保留，供人工核对偏移。
 * ★ v1.4.53：原来把全部可执行段都列出来（~100 行、约 10KB），
 *   结果被 LogUploader 的 12K 头部上限拦腰截断，远端看不到关键模块。
 *   现在只列「可疑/相关」模块（厂商库 / 图形 / 本 App）+ 崩溃点所在模块，总量 <40 行。 */
static void put_exec_modules(int fd, uintptr_t pc, uintptr_t lr) {
    put_str(fd, "\n===== 相关可执行模块（含加载基址）=====\n");
    const char* seen[UNIQ_NAME_MAX];
    int nseen = 0;
    int printed = 0;
    struct ent* must[2] = { find_ent(pc), find_ent(lr) };

    for (int pass = 0; pass < 2; pass++) {
        for (int i = 0; i < g_n; i++) {
            struct ent* e = &g_ent[i];
            if (!e->exec) continue;
            const char* nm = (e->name && e->name[0]) ? e->name : "(匿名)";

            if (pass == 0) {
                /* 第一遍：崩溃点 / lr 所在模块，无论如何都要列出来 */
                int need = 0;
                for (int k = 0; k < 2; k++) if (must[k] == e) need = 1;
                if (!need) continue;
            } else {
                /* 第二遍：只列名字里带关键词的模块 */
                int interesting = 0;
                static const char* KEYS[] = {
                    "car", "ecarx", "geely", "gui", "hvac", "dockbar",
                    "hwui", "gralloc", "adreno", "surface", "binder", "cutils", "utils", 0
                };
                for (int k = 0; KEYS[k]; k++) {
                    if (strstr(nm, KEYS[k])) { interesting = 1; break; }
                }
                if (!interesting) continue;
            }

            int dup = 0;
            for (int k = 0; k < nseen; k++) {
                if (seen[k] && strcmp(seen[k], nm) == 0) { dup = 1; break; }
            }
            if (dup) continue;
            if (nseen < UNIQ_NAME_MAX) seen[nseen++] = nm;

            put_hex(fd, e->start);
            put_str(fd, "-");
            put_hex(fd, e->end);
            put_str(fd, "  ");
            put_str(fd, nm);
            put_str(fd, "\n");
            printed++;
            if (printed >= 40) break;
        }
        if (printed >= 40) break;
    }
    if (printed == 0) put_str(fd, "(没解析到任何可执行映射)\n");
}

/* 栈上落在可执行段的字 ≈ 调用链上的返回地址 */
static void put_call_chain(int fd, uintptr_t sp) {
    put_str(fd, "\n===== 栈上疑似返回地址（调用链，自栈顶向下）=====\n");
    if (sp == 0) { put_str(fd, "(SP 未知)\n"); return; }
    const uintptr_t* p = (const uintptr_t*)sp;
    int idx = 0;
    for (int i = 0; i < STACK_DUMP_WORDS && idx < CALL_CHAIN_MAX; i++) {
        uintptr_t v = p[i];
        if (v < 0x10000) continue;
        if (v & 3) continue;
        struct ent* e = find_ent(v);
        if (!e || !e->exec) continue;
        put_str(fd, "  #");
        put_ll(fd, idx);
        put_str(fd, "  ");
        put_resolve(fd, v);
        put_str(fd, "\n");
        idx++;
    }
    if (idx == 0) put_str(fd, "(栈上没找到落在可执行段的返回地址)\n");
}

static void dump_stack(int fd, uintptr_t sp) {
    put_str(fd, "\n===== 栈内存（前 64 字）=====\n");
    if (sp == 0) { put_str(fd, "(SP 未知)\n"); return; }
    const uintptr_t* p = (const uintptr_t*)sp;
    for (int i = 0; i < 64; i++) {
        if (i % 4 == 0) {
            put_str(fd, "  ");
            put_hex(fd, sp + (uintptr_t)i * sizeof(uintptr_t));
            put_str(fd, ": ");
        }
        put_hex(fd, p[i]);
        put_str(fd, " ");
        if (i % 4 == 3) put_str(fd, "\n");
    }
    put_str(fd, "\n");
}

/* ---------------- 信号处理器 ---------------- */

static void crash_handler(int sig, siginfo_t* info, void* ucontext) {
    int fd = open(g_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) {
        ucontext_t* uc = (ucontext_t*)ucontext;

        put_str(fd, "===== Native 崩溃 =====\n");
        put_str(fd, "signal      : "); put_ll(fd, sig); put_str(fd, "  "); put_str(fd, sig_name(sig)); put_str(fd, "\n");
        put_str(fd, "si_code     : ");
        put_ll(fd, info ? (long long)info->si_code : 0);
        if (info) {
            if (info->si_code == 1)      put_str(fd, "  (SEGV_MAPERR 地址未映射)");
            else if (info->si_code == 2) put_str(fd, "  (SEGV_ACCERR 地址已映射但权限不足)");
            else if (info->si_code == -6) {
                /* SI_TKILL：本进程自己 tgkill(abort)。si_addr 与 si_pid/si_uid 是同一个 union */
                unsigned long long a = (unsigned long long)(uintptr_t)info->si_addr;
                unsigned pid = (unsigned)(a & 0xffffffffu);
                unsigned uid = (unsigned)((a >> 32) & 0xffffffffu);
                put_str(fd, "  (SI_TKILL：本进程主动 abort；发送方 pid=");
                put_ll(fd, pid);
                put_str(fd, " uid=");
                put_ll(fd, uid);
                put_str(fd, ")");
            }
        }
        put_str(fd, "\n");
        put_str(fd, "fault addr  : "); put_hex(fd, (uintptr_t)(info ? info->si_addr : 0)); put_str(fd, "\n");
        put_str(fd, "pc (崩溃点) : "); put_hex(fd, uc ? UC_PC(uc) : 0); put_str(fd, "\n");
        put_str(fd, "lr          : "); put_hex(fd, uc ? UC_LR(uc) : 0); put_str(fd, "\n");
        put_str(fd, "sp          : "); put_hex(fd, uc ? UC_SP(uc) : 0); put_str(fd, "\n");
        put_thread_name(fd);

        /* ★ 先把 maps 读进内存并解析，后面所有地址都能换算成「模块+偏移」 */
        read_maps();
        parse_maps();

        if (uc) {
            put_str(fd, "\n===== 崩溃点归属 =====\n");
            put_str(fd, "  pc → "); put_resolve(fd, UC_PC(uc)); put_str(fd, "\n");
            put_str(fd, "  lr → "); put_resolve(fd, UC_LR(uc)); put_str(fd, "\n");
            put_str(fd, "  sp → "); put_resolve(fd, UC_SP(uc)); put_str(fd, "\n");
        }

        put_exec_modules(fd, uc ? UC_PC(uc) : 0, uc ? UC_LR(uc) : 0);
        if (uc) put_call_chain(fd, UC_SP(uc));
        if (uc) dump_stack(fd, UC_SP(uc));

        put_str(fd, "\n提示：模块名 + 偏移是定位关键。若 pc 落在厂商自带的 so 库之类的\n"
                    "第三方库，说明是第三方 native 代码对普通 App 主动 abort；若落在 libc.so，\n"
                    "多半是断言 / 堆破坏被 libc 检测到后 abort，真正原因是上面的调用链。\n");
        close(fd);
    }

    /* 恢复该信号的默认处理，再重新触发，保持系统原有的崩溃语义 */
    for (int i = 0; i < g_signal_count; i++) {
        if (g_signals[i] == sig) {
            sigaction(sig, &g_old_actions[i], NULL);
            break;
        }
    }
    raise(sig);
}

/* ---------------- JNI ---------------- */

JNIEXPORT jboolean JNICALL
Java_com_car_dockbar_util_NativeCrashHandler_nativeInstall(JNIEnv* env, jclass clazz, jstring path) {
    (void)clazz;
    if (g_installed) return JNI_TRUE;
    if (path == NULL) return JNI_FALSE;

    const char* p = (*env)->GetStringUTFChars(env, path, NULL);
    if (p == NULL) return JNI_FALSE;
    size_t len = strlen(p);
    if (len == 0 || len >= PATH_MAX_LEN - 8) {
        (*env)->ReleaseStringUTFChars(env, path, p);
        return JNI_FALSE;
    }
    memcpy(g_path, p, len);
    g_path[len] = '\0';
    (*env)->ReleaseStringUTFChars(env, path, p);

    /* ★ stderr → 文件：libc 在 abort 前会把原因（assert / 堆破坏）写到 fd 2，
       应用进程默认 fd 2 是 /dev/null，重定向后下次启动可回捞。 */
    char errpath[PATH_MAX_LEN + 8];
    memcpy(errpath, g_path, len);
    memcpy(errpath + len, ".err", 5);
    int ef = open(errpath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (ef >= 0) {
        dup2(ef, 2);
        if (ef != 2) close(ef);
    }

    /* 备用信号栈：栈溢出导致的 SIGSEGV 也能被处理 */
    static char alt_stack[SIGSTKSZ * 4];
    stack_t ss;
    ss.ss_sp = alt_stack;
    ss.ss_size = sizeof(alt_stack);
    ss.ss_flags = 0;
    sigaltstack(&ss, NULL);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;
    sigemptyset(&sa.sa_mask);

    int ok = 0;
    for (int i = 0; i < g_signal_count; i++) {
        if (sigaction(g_signals[i], &sa, &g_old_actions[i]) == 0) ok++;
    }
    g_installed = (ok > 0) ? 1 : 0;
    return g_installed ? JNI_TRUE : JNI_FALSE;
}
