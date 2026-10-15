/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <time.h>
#include <elf.h>
#include <atomic>
#include <jni.h>

#include "l2c_fcr_hook.h"

extern "C" {
#include "xz.h"
}

#define LOG_TAG "LibrePodsHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static HookFunType hook_func = nullptr;

static uint8_t (*original_l2c_fcr_chk_chan_modes)(void *) = nullptr;

static tBTA_STATUS (*original_BTA_DmSetLocalDiRecord)(tSDP_DI_RECORD *, uint32_t *) = nullptr;

static std::atomic<bool> enableSdpHook(false);

static int32_t (*original_A2dpStreamCallbacks_StartStream)(const void *, bool) = nullptr;

static std::atomic<int64_t> a2dpStartGuardExpiryNs(0);
static std::atomic<bool> a2dpStartHookInstalled(false);

static constexpr char A2DP_START_STREAM_SYMBOL[] =
        "_ZNK19A2dpStreamCallbacks11StartStreamEb";
static constexpr int32_t A2DP_CTRL_ACK_SUCCESS = 0;
static constexpr int32_t A2DP_CTRL_ACK_FAILURE = 3;
static constexpr int64_t MAX_A2DP_START_GUARD_LEASE_MS = 10'000;
// How long after a lease expires a start attempt is still worth reporting as a lapse.
static constexpr int64_t LAPSED_LEASE_REPORT_WINDOW_MS = 60'000;
static constexpr int64_t NANOS_PER_MILLISECOND = 1'000'000;
static constexpr int64_t NANOS_PER_SECOND = 1'000'000'000;

static int64_t monotonicTimeNs() {
    struct timespec now{};
    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0) {
        return 0;
    }
    return static_cast<int64_t>(now.tv_sec) * NANOS_PER_SECOND + now.tv_nsec;
}

static bool isA2dpStartGuardLeaseActive() {
    const int64_t expiryNs = a2dpStartGuardExpiryNs.load(std::memory_order_relaxed);
    if (expiryNs <= 0) {
        return false;
    }

    const int64_t nowNs = monotonicTimeNs();
    return nowNs > 0 && nowNs < expiryNs;
}

int32_t fake_A2dpStreamCallbacks_StartStream(const void *self, bool lowLatency) {
    if (isA2dpStartGuardLeaseActive()) {
        LOGI("A2DP StartStream blocked by active guard lease");
        return A2DP_CTRL_ACK_FAILURE;
    }

    // A start that arrives just after the lease ran out is the signature of the app being
    // unable to renew (HyperOS froze it). Without this the fail-open path is completely
    // silent and the guard's most important failure is invisible in a bug report.
    const int64_t expiryNs = a2dpStartGuardExpiryNs.load(std::memory_order_relaxed);
    if (expiryNs > 0) {
        const int64_t nowNs = monotonicTimeNs();
        if (nowNs > 0) {
            const int64_t lapsedMs = (nowNs - expiryNs) / NANOS_PER_MILLISECOND;
            if (lapsedMs >= 0 && lapsedMs <= LAPSED_LEASE_REPORT_WINDOW_MS) {
                LOGW("A2DP StartStream allowed %lldms after the guard lease lapsed; "
                     "the app could not renew it",
                     (long long) lapsedMs);
            }
        }
    }

    if (!original_A2dpStreamCallbacks_StartStream) {
        // Fail open: a missing original must never leave A2DP permanently broken.
        LOGE("A2DP StartStream original function unavailable; allowing the stream");
        return A2DP_CTRL_ACK_SUCCESS;
    }
    return original_A2dpStreamCallbacks_StartStream(self, lowLatency);
}

static bool isBluetoothProcess() {
    int fd = open("/proc/self/cmdline", O_RDONLY);
    if (fd < 0) {
        LOGE("cannot read process name; native hooks will stay disabled");
        return false;
    }

    char cmdline[256] = {};
    ssize_t length = read(fd, cmdline, sizeof(cmdline) - 1);
    close(fd);
    if (length <= 0) {
        LOGE("empty process name; native hooks will stay disabled");
        return false;
    }

    const std::string processName(cmdline);
    const bool allowed = processName.rfind("com.android.bluetooth", 0) == 0
            || processName.rfind("com.google.android.bluetooth", 0) == 0;
    if (!allowed) {
        LOGI("native hooks disabled outside Bluetooth process: %s", processName.c_str());
    }
    return allowed;
}

uint8_t fake_l2c_fcr_chk_chan_modes(void *p_ccb) {
    LOGI("fake_l2c_fcr_chk_chan_modes called");
    uint8_t orig = 0;
    if (original_l2c_fcr_chk_chan_modes)
        orig = original_l2c_fcr_chk_chan_modes(p_ccb);

    LOGI("fake_l2c_fcr_chk_chan_modes: orig = %d, returning 1", orig);
    return 1;
}

tBTA_STATUS fake_BTA_DmSetLocalDiRecord(tSDP_DI_RECORD *p_device_info, uint32_t *p_handle) {

    LOGI("fake_BTA_DmSetLocalDiRecord called");

    if (original_BTA_DmSetLocalDiRecord &&
        enableSdpHook.load(std::memory_order_relaxed))
        original_BTA_DmSetLocalDiRecord(p_device_info, p_handle);

    LOGI("fake_BTA_DmSetLocalDiRecord: modifying vendor to 0x004C, vendor_id_source to 0x0001");

    if (p_device_info) {
        p_device_info->vendor = 0x004C;
        p_device_info->vendor_id_source = 0x0001;
    }

    LOGI("fake_BTA_DmSetLocalDiRecord: returning status %d",
         original_BTA_DmSetLocalDiRecord ? original_BTA_DmSetLocalDiRecord(p_device_info, p_handle)
                                         : BTA_FAILURE);
    return original_BTA_DmSetLocalDiRecord ? original_BTA_DmSetLocalDiRecord(p_device_info,
                                                                             p_handle)
                                           : BTA_FAILURE;
}

static bool decompressXZ(const uint8_t *input, size_t input_size, std::vector<uint8_t> &output) {

    LOGI("decompressXZ called with input_size: %zu", input_size);

    xz_crc32_init();
#ifdef XZ_USE_CRC64
    xz_crc64_init();
#endif

    struct xz_dec *dec = xz_dec_init(XZ_DYNALLOC, 64U << 20);
    if (!dec) {
        LOGE("decompressXZ: xz_dec_init failed");
        return false;
    }
    LOGI("decompressXZ: xz_dec_init succeeded");

    struct xz_buf buf{};
    buf.in = input;
    buf.in_pos = 0;
    buf.in_size = input_size;

    output.resize(input_size * 8);

    buf.out = output.data();
    buf.out_pos = 0;
    buf.out_size = output.size();

    LOGI("decompressXZ: entering decompression loop");
    while (true) {
        LOGI("decompressXZ: xz_dec_run iteration, buf.in_pos: %zu, buf.out_pos: %zu", buf.in_pos,
             buf.out_pos);
        enum xz_ret ret = xz_dec_run(dec, &buf);

        LOGI("decompressXZ: xz_dec_run returned %d", ret);

        if (ret == XZ_STREAM_END)
            break;

        if (ret != XZ_OK) {
            LOGE("decompressXZ: xz_dec_run error");
            xz_dec_end(dec);
            return false;
        }

        if (buf.out_pos == buf.out_size) {
            size_t old = output.size();
            LOGI("decompressXZ: resizing output to %zu", old * 2);
            output.resize(old * 2);
            buf.out = output.data();
            buf.out_size = output.size();
        }
    }

    output.resize(buf.out_pos);
    xz_dec_end(dec);
    LOGI("decompressXZ: decompression successful, output size: %zu", output.size());
    return true;
}

static bool getLibraryPath(const char *name, std::string &out) {
    LOGI("getLibraryPath called with name: %s", name);

    FILE *fp = fopen("/proc/self/maps", "r");
    if (!fp) {
        LOGE("getLibraryPath: fopen failed");
        return false;
    }

    char line[1024];

    LOGI("getLibraryPath: scanning /proc/self/maps");
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, name)) {
            LOGI("getLibraryPath: found line containing %s", name);
            char *path = strchr(line, '/');
            if (path) {
                out = path;
                out.erase(out.find('\n'));
                LOGI("getLibraryPath: path found: %s", out.c_str());
                fclose(fp);
                return true;
            }
        }
    }

    fclose(fp);
    LOGI("getLibraryPath: failed to find path for %s", name);
    return false;
}

static uintptr_t getModuleBase(const char *name) {
    LOGI("getModuleBase called with name: %s", name);

    FILE *fp = fopen("/proc/self/maps", "r");
    if (!fp) {
        LOGE("getModuleBase: fopen failed");
        return 0;
    }

    char line[1024];
    uintptr_t base = 0;

    LOGI("getModuleBase: scanning /proc/self/maps");
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, name)) {
            base = strtoull(line, nullptr, 16);
            LOGI("getModuleBase: found base at 0x%lx", base);
            break;
        }
    }

    fclose(fp);
    LOGI("getModuleBase: failed to find base for %s", name);
    return base;
}

static uint64_t
findSymbolOffsetDynsym(const std::vector<uint8_t> &elf, const char *symbol_substring) {

    LOGI("findSymbolOffsetDynsym called with %s", symbol_substring);

    auto *eh = reinterpret_cast<const Elf64_Ehdr *>(elf.data());
    auto *shdr = reinterpret_cast<const Elf64_Shdr *>(
            elf.data() + eh->e_shoff);

    const char *shstr = reinterpret_cast<const char *>(
            elf.data() + shdr[eh->e_shstrndx].sh_offset);

    const Elf64_Shdr *dynsym = nullptr;
    const Elf64_Shdr *dynstr = nullptr;

    for (int i = 0; i < eh->e_shnum; ++i) {
        const char *secname = shstr + shdr[i].sh_name;

        if (!strcmp(secname, ".dynsym"))
            dynsym = &shdr[i];
        if (!strcmp(secname, ".dynstr"))
            dynstr = &shdr[i];
    }

    if (!dynsym || !dynstr) {
        LOGE("findSymbolOffsetDynsym: dynsym or dynstr not found");
        return 0;
    }

    auto *symbols = reinterpret_cast<const Elf64_Sym *>(
            elf.data() + dynsym->sh_offset);

    const char *strings = reinterpret_cast<const char *>(
            elf.data() + dynstr->sh_offset);

    size_t count = dynsym->sh_size / sizeof(Elf64_Sym);

    LOGI("findSymbolOffsetDynsym: scanning %zu symbols", count);

    for (size_t i = 0; i < count; ++i) {
        const char *name = strings + symbols[i].st_name;

        if (strstr(name, symbol_substring) && ELF64_ST_TYPE(symbols[i].st_info) == STT_FUNC) {

            LOGI("findSymbolOffsetDynsym: matched %s @ 0x%lx", name,
                 (unsigned long) symbols[i].st_value);

            return symbols[i].st_value;
        }
    }

    LOGI("findSymbolOffsetDynsym: no match for %s", symbol_substring);
    return 0;
}

static uint64_t findSymbolOffset(const std::vector<uint8_t> &elf, const char *symbol_substring) {

    LOGI("findSymbolOffset called with symbol_substring: %s", symbol_substring);

    auto *eh = reinterpret_cast<const Elf64_Ehdr *>(elf.data());
    auto *shdr = reinterpret_cast<const Elf64_Shdr *>(
            elf.data() + eh->e_shoff);

    const char *shstr = reinterpret_cast<const char *>(
            elf.data() + shdr[eh->e_shstrndx].sh_offset);

    const Elf64_Shdr *symtab = nullptr;
    const Elf64_Shdr *strtab = nullptr;

    LOGI("findSymbolOffset: parsing ELF sections");
    for (int i = 0; i < eh->e_shnum; ++i) {
        const char *secname = shstr + shdr[i].sh_name;
        if (!strcmp(secname, ".symtab"))
            symtab = &shdr[i];
        if (!strcmp(secname, ".strtab"))
            strtab = &shdr[i];
    }

    if (!symtab || !strtab) {
        LOGE("findSymbolOffset: symtab or strtab not found");
        return 0;
    }
    LOGI("findSymbolOffset: found symtab and strtab");

    auto *symbols = reinterpret_cast<const Elf64_Sym *>(
            elf.data() + symtab->sh_offset);

    const char *strings = reinterpret_cast<const char *>(
            elf.data() + strtab->sh_offset);

    size_t count = symtab->sh_size / sizeof(Elf64_Sym);

    LOGI("findSymbolOffset: scanning %zu symbols", count);
    for (size_t i = 0; i < count; ++i) {
        const char *name = strings + symbols[i].st_name;

        if (strstr(name, symbol_substring) && ELF64_ST_TYPE(symbols[i].st_info) == STT_FUNC) {

            LOGI("findSymbolOffset: matched symbol %s at 0x%lx", name,
                 (unsigned long) symbols[i].st_value);

            return symbols[i].st_value;
        }
    }

    LOGI("findSymbolOffset: no match found for %s", symbol_substring);
    return 0;
}

struct ExactSymbolLookupResult {
    uint64_t offset = 0;
    size_t matches = 0;
    bool parsed = false;
};

static bool elfRangeIsValid(const std::vector<uint8_t> &elf, uint64_t offset, uint64_t size) {
    return offset <= elf.size() && size <= elf.size() - offset;
}

static ExactSymbolLookupResult findUniqueExactSymbolOffset(
        const std::vector<uint8_t> &elf,
        const char *symbolName,
        const char *symbolSectionName,
        const char *stringSectionName) {
    ExactSymbolLookupResult result;
    if (elf.size() < sizeof(Elf64_Ehdr)) {
        return result;
    }

    const auto *header = reinterpret_cast<const Elf64_Ehdr *>(elf.data());
    if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0 ||
        header->e_ident[EI_CLASS] != ELFCLASS64 ||
        header->e_shentsize != sizeof(Elf64_Shdr) ||
        header->e_shnum == 0 ||
        header->e_shstrndx >= header->e_shnum ||
        !elfRangeIsValid(elf, header->e_shoff,
                         static_cast<uint64_t>(header->e_shnum) * sizeof(Elf64_Shdr))) {
        return result;
    }

    const auto *sections = reinterpret_cast<const Elf64_Shdr *>(
            elf.data() + header->e_shoff);
    const Elf64_Shdr &sectionNamesSection = sections[header->e_shstrndx];
    if (!elfRangeIsValid(elf, sectionNamesSection.sh_offset, sectionNamesSection.sh_size)) {
        return result;
    }
    const char *sectionNames = reinterpret_cast<const char *>(
            elf.data() + sectionNamesSection.sh_offset);

    const Elf64_Shdr *symbolSection = nullptr;
    const Elf64_Shdr *stringSection = nullptr;
    for (size_t i = 0; i < header->e_shnum; ++i) {
        if (sections[i].sh_name >= sectionNamesSection.sh_size) {
            return result;
        }
        const char *sectionName = sectionNames + sections[i].sh_name;
        const size_t maximumNameLength = sectionNamesSection.sh_size - sections[i].sh_name;
        if (!memchr(sectionName, '\0', maximumNameLength)) {
            return result;
        }

        if (strcmp(sectionName, symbolSectionName) == 0) {
            symbolSection = &sections[i];
        } else if (strcmp(sectionName, stringSectionName) == 0) {
            stringSection = &sections[i];
        }
    }

    if (!symbolSection || !stringSection ||
        symbolSection->sh_entsize != sizeof(Elf64_Sym) ||
        symbolSection->sh_size % sizeof(Elf64_Sym) != 0 ||
        !elfRangeIsValid(elf, symbolSection->sh_offset, symbolSection->sh_size) ||
        !elfRangeIsValid(elf, stringSection->sh_offset, stringSection->sh_size)) {
        return result;
    }

    const auto *symbols = reinterpret_cast<const Elf64_Sym *>(
            elf.data() + symbolSection->sh_offset);
    const char *strings = reinterpret_cast<const char *>(elf.data() + stringSection->sh_offset);
    const size_t symbolCount = symbolSection->sh_size / sizeof(Elf64_Sym);
    result.parsed = true;

    for (size_t i = 0; i < symbolCount; ++i) {
        if (symbols[i].st_name >= stringSection->sh_size) {
            continue;
        }
        const char *name = strings + symbols[i].st_name;
        const size_t maximumNameLength = stringSection->sh_size - symbols[i].st_name;
        if (!memchr(name, '\0', maximumNameLength)) {
            continue;
        }
        if (strcmp(name, symbolName) == 0 &&
            ELF64_ST_TYPE(symbols[i].st_info) == STT_FUNC &&
            symbols[i].st_shndx != SHN_UNDEF) {
            ++result.matches;
            result.offset = symbols[i].st_value;
        }
    }

    if (result.matches != 1) {
        result.offset = 0;
    }
    return result;
}

static bool hookLibrary(const char *libname) {
    LOGI("hookLibrary called with libname: %s", libname);

    if (!hook_func) {
        LOGE("hook_func not initialized");
        return false;
    }

    std::string path;
    if (!getLibraryPath(libname, path)) {
        LOGE("Failed to locate %s", libname);
        return false;
    }
    LOGI("hookLibrary: located path: %s", path.c_str());

    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) {
        LOGE("hookLibrary: open failed");
        return false;
    }

    struct stat st{};
    if (fstat(fd, &st) != 0) {
        LOGE("hookLibrary: fstat failed");
        close(fd);
        return false;
    }
    LOGI("hookLibrary: opened file, size: %lld", (long long) st.st_size);

    std::vector<uint8_t> file(st.st_size);
    read(fd, file.data(), st.st_size);
    close(fd);

    auto *eh = reinterpret_cast<Elf64_Ehdr *>(file.data());
    auto *shdr = reinterpret_cast<Elf64_Shdr *>(
            file.data() + eh->e_shoff);

    const char *shstr = reinterpret_cast<const char *>(
            file.data() + shdr[eh->e_shstrndx].sh_offset);

    uint64_t chk_offset = 0;
    uint64_t sdp_offset = 0;
    uint64_t a2dp_start_offset = 0;
    bool rejectA2dpStartSymbol = false;
    const bool resolveA2dpStart = strcmp(libname, "libbluetooth_jni.so") == 0 &&
            !a2dpStartHookInstalled.load(std::memory_order_relaxed);

    for (int i = 0; i < eh->e_shnum; ++i) {
        if (!strcmp(shstr + shdr[i].sh_name, ".gnu_debugdata")) {
            LOGI("hookLibrary: found .gnu_debugdata section");

            std::vector<uint8_t> compressed(file.begin() + shdr[i].sh_offset,
                                            file.begin() + shdr[i].sh_offset + shdr[i].sh_size);

            std::vector<uint8_t> decompressed;

            if (decompressXZ(compressed.data(), compressed.size(), decompressed)) {

                chk_offset = findSymbolOffset(decompressed, "l2c_fcr_chk_chan_modes");

                sdp_offset = findSymbolOffset(decompressed, "BTA_DmSetLocalDiRecord");

                if (resolveA2dpStart) {
                    const ExactSymbolLookupResult lookup = findUniqueExactSymbolOffset(
                            decompressed,
                            A2DP_START_STREAM_SYMBOL,
                            ".symtab",
                            ".strtab");
                    if (lookup.matches == 1 && lookup.offset != 0) {
                        a2dp_start_offset = lookup.offset;
                        LOGI("hookLibrary: exact A2DP StartStream symbol @ 0x%lx",
                             (unsigned long) a2dp_start_offset);
                    } else if (lookup.matches > 1 ||
                               (lookup.matches == 1 && lookup.offset == 0)) {
                        rejectA2dpStartSymbol = true;
                        LOGE("hookLibrary: rejecting ambiguous/invalid A2DP StartStream symbol (%zu matches)",
                             lookup.matches);
                    } else if (!lookup.parsed) {
                        LOGE("hookLibrary: could not parse debug symbol table for A2DP StartStream");
                    }
                }
            } else {
                LOGE("debugdata decompress failed");
            }

            break;
        }
    }

    if (!chk_offset) {
        LOGI("fallback dynsym chk");
        chk_offset = findSymbolOffsetDynsym(file, "l2c_fcr_chk_chan_modes");
    }

    if (!sdp_offset) {
        LOGI("fallback dynsym sdp");
        sdp_offset = findSymbolOffsetDynsym(file, "BTA_DmSetLocalDiRecord");
    }

    if (resolveA2dpStart && !a2dp_start_offset && !rejectA2dpStartSymbol) {
        const ExactSymbolLookupResult lookup = findUniqueExactSymbolOffset(
                file,
                A2DP_START_STREAM_SYMBOL,
                ".dynsym",
                ".dynstr");
        if (lookup.matches == 1 && lookup.offset != 0) {
            a2dp_start_offset = lookup.offset;
            LOGI("hookLibrary: exact dynsym A2DP StartStream symbol @ 0x%lx",
                 (unsigned long) a2dp_start_offset);
        } else {
            rejectA2dpStartSymbol = true;
            LOGE("hookLibrary: exact A2DP StartStream symbol unavailable (%zu dynsym matches); guard stays fail-open",
                 lookup.matches);
        }
    }

    uintptr_t base = getModuleBase(libname);
    if (!base) {
        LOGE("hookLibrary: getModuleBase failed");
        return false;
    }

    if (chk_offset) {
        void *target = reinterpret_cast<void *>(base + chk_offset);
        hook_func(target, (void *) fake_l2c_fcr_chk_chan_modes,
                  (void **) &original_l2c_fcr_chk_chan_modes);
        LOGI("hooked chk");
    }

    if (sdp_offset) {
        void *target = reinterpret_cast<void *>(base + sdp_offset);
        hook_func(target, (void *) fake_BTA_DmSetLocalDiRecord,
                  (void **) &original_BTA_DmSetLocalDiRecord);
        LOGI("hooked sdp");
    }

    bool a2dpStartHooked = false;
    if (a2dp_start_offset && !rejectA2dpStartSymbol &&
        !a2dpStartHookInstalled.load(std::memory_order_relaxed)) {
        void *target = reinterpret_cast<void *>(base + a2dp_start_offset);
        const int hookStatus = hook_func(
                target,
                (void *) fake_A2dpStreamCallbacks_StartStream,
                (void **) &original_A2dpStreamCallbacks_StartStream);
        if (hookStatus == 0 && original_A2dpStreamCallbacks_StartStream) {
            a2dpStartHookInstalled.store(true, std::memory_order_relaxed);
            a2dpStartHooked = true;
            LOGI("hooked exact A2DP StartStream");
        } else {
            a2dpStartGuardExpiryNs.store(0, std::memory_order_relaxed);
            LOGE("failed to hook A2DP StartStream (status=%d, original=%p); guard stays fail-open",
                 hookStatus, original_A2dpStreamCallbacks_StartStream);
        }
    }

    return chk_offset || sdp_offset || a2dpStartHooked;
}

static void on_library_loaded(const char *name, void *) {
    LOGI("on_library_loaded called with name: %s", name);

    if (strstr(name, "libbluetooth_jni.so")) {
        LOGI("Bluetooth JNI loaded");
        hookLibrary("libbluetooth_jni.so");
    }

    if (strstr(name, "libbluetooth_qti.so")) {
        LOGI("Bluetooth QTI loaded");
        hookLibrary("libbluetooth_qti.so");
    }
}

extern "C" [[gnu::visibility("default")]]
[[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    LOGI("native_init called with entries: %p", entries);
    if (!isBluetoothProcess()) {
        return nullptr;
    }
    hook_func = (HookFunType) entries->hook_func;
    LOGI("LibrePodsNativeHook initialized, sdp hook enabled: %d",
         enableSdpHook.load(std::memory_order_relaxed));
    return on_library_loaded;
}

extern "C" JNIEXPORT void JNICALL
Java_me_kavishdevar_librepods_utils_NativeBridge_setSdpHook(JNIEnv *, jobject thiz,
                                                            jboolean enable) {
    LOGI("setSdpHook called with enable: %d", enable);
    enableSdpHook.store(enable, std::memory_order_relaxed);

    LOGI("sdp hook enabled: %d", enable);
}

extern "C" JNIEXPORT void JNICALL
Java_me_kavishdevar_librepods_utils_NativeBridge_setA2dpStartGuardLease(
        JNIEnv *, jobject, jlong remainingMs) {
    if (remainingMs <= 0) {
        const int64_t previousExpiryNs = a2dpStartGuardExpiryNs.exchange(
                0, std::memory_order_relaxed);
        if (previousExpiryNs > 0) {
            LOGI("A2DP StartStream guard lease cleared");
        }
        return;
    }

    const int64_t clampedRemainingMs = remainingMs > MAX_A2DP_START_GUARD_LEASE_MS
            ? MAX_A2DP_START_GUARD_LEASE_MS
            : remainingMs;
    const int64_t nowNs = monotonicTimeNs();
    if (nowNs <= 0) {
        a2dpStartGuardExpiryNs.store(0, std::memory_order_relaxed);
        LOGE("A2DP StartStream guard clock unavailable; lease cleared");
        return;
    }

    const int64_t expiryNs = nowNs + clampedRemainingMs * NANOS_PER_MILLISECOND;
    const int64_t previousExpiryNs = a2dpStartGuardExpiryNs.exchange(
            expiryNs, std::memory_order_relaxed);
    if (previousExpiryNs <= nowNs) {
        LOGI("A2DP StartStream guard lease activated for %lldms",
             (long long) clampedRemainingMs);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_kavishdevar_librepods_utils_NativeBridge_isA2dpStartGuardHookInstalled(JNIEnv *, jobject) {
    return a2dpStartHookInstalled.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}
