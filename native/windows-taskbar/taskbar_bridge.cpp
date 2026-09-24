#include <windows.h>
#include <commctrl.h>
#include <shobjidl_core.h>

#include <filesystem>
#include <fstream>
#include <iomanip>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>

namespace {

constexpr UINT kCommandPrevious = 40001;
constexpr UINT kCommandPlayPause = 40002;
constexpr UINT kCommandNext = 40003;
constexpr int kActionPrevious = 1;
constexpr int kActionPlayPause = 2;
constexpr int kActionNext = 3;
constexpr int kActionThemeChanged = 4;
constexpr int kActionButtonsApplied = 5;
constexpr int kActionButtonsApplyFailed = 6;
constexpr int kActionSubclassInstalled = 7;
constexpr int kActionSubclassInstallFailed = 8;
constexpr UINT_PTR kRetryTimerId = 0x4C617A66;
constexpr UINT kRetryIntervalMillis = 100;
constexpr UINT kApplyButtonsMessage = WM_APP + 0x4C61;
constexpr UINT kInstallSubclassMessage = WM_APP + 0x4C62;
constexpr UINT_PTR kTaskbarSubclassId = 0x4C617A72;
constexpr uintmax_t kMaxDebugLogBytes = 512 * 1024;

using ActionCallback = void(__stdcall*)(int action);

LRESULT CALLBACK TaskbarCallWindowHook(int code, WPARAM wParam, LPARAM lParam);
LRESULT CALLBACK TaskbarGetMessageHook(int code, WPARAM wParam, LPARAM lParam);

struct TaskbarState {
    ITaskbarList3* taskbar = nullptr;
    ActionCallback callback = nullptr;
    HICON previousIcon = nullptr;
    HICON playIcon = nullptr;
    HICON pauseIcon = nullptr;
    HICON nextIcon = nullptr;
    std::wstring previousTooltip;
    std::wstring playTooltip;
    std::wstring pauseTooltip;
    std::wstring nextTooltip;
    HWND hwnd = nullptr;
    DWORD ownerThreadId = 0;
    HHOOK callWindowHook = nullptr;
    HHOOK getMessageHook = nullptr;
    bool buttonsAdded = false;
    bool isPlaying = false;

    ~TaskbarState() {
        if (callWindowHook != nullptr) UnhookWindowsHookEx(callWindowHook);
        if (getMessageHook != nullptr) UnhookWindowsHookEx(getMessageHook);
        if (taskbar != nullptr) taskbar->Release();
        if (previousIcon != nullptr) DestroyIcon(previousIcon);
        if (playIcon != nullptr) DestroyIcon(playIcon);
        if (pauseIcon != nullptr) DestroyIcon(pauseIcon);
        if (nextIcon != nullptr) DestroyIcon(nextIcon);
    }
};

std::unordered_map<HWND, std::unique_ptr<TaskbarState>> g_states;
UINT g_taskbarButtonCreated = 0;
std::mutex g_debugLogMutex;
std::wstring g_debugLogPath;

std::wstring WindowDetails(HWND hwnd) {
    wchar_t className[256]{};
    GetClassNameW(hwnd, className, ARRAYSIZE(className));
    DWORD processId = 0;
    const DWORD threadId = GetWindowThreadProcessId(hwnd, &processId);
    std::wstringstream value;
    value << L"hwnd=0x" << std::hex << reinterpret_cast<UINT_PTR>(hwnd)
          << L" class=" << className
          << L" pid=" << std::dec << processId
          << L" thread=" << threadId
          << L" visible=" << (IsWindowVisible(hwnd) ? 1 : 0)
          << L" style=0x" << std::hex << static_cast<ULONG_PTR>(GetWindowLongPtrW(hwnd, GWL_STYLE));
    return value.str();
}

void DebugLog(const wchar_t* event, const std::wstring& details = L"") {
    std::lock_guard lock(g_debugLogMutex);
    if (g_debugLogPath.empty()) return;
    const std::filesystem::path path(g_debugLogPath);
    std::error_code error;
    std::filesystem::create_directories(path.parent_path(), error);
    if (std::filesystem::exists(path, error) && std::filesystem::file_size(path, error) >= kMaxDebugLogBytes) {
        std::filesystem::rename(path, path.parent_path() / L"taskbar-native.previous.log", error);
        if (error) {
            error.clear();
            std::filesystem::remove(path.parent_path() / L"taskbar-native.previous.log", error);
            error.clear();
            std::filesystem::rename(path, path.parent_path() / L"taskbar-native.previous.log", error);
        }
    }
    std::wofstream output(path, std::ios::app);
    if (!output) return;
    SYSTEMTIME now{};
    GetSystemTime(&now);
    output << std::setfill(L'0')
           << now.wYear << L'-' << std::setw(2) << now.wMonth << L'-' << std::setw(2) << now.wDay
           << L'T' << std::setw(2) << now.wHour << L':' << std::setw(2) << now.wMinute
           << L':' << std::setw(2) << now.wSecond << L'.' << std::setw(3) << now.wMilliseconds
           << L"Z [thread=" << GetCurrentThreadId() << L"] " << event << L' ' << details << L'\n';
}

void DebugMessage(const wchar_t* source, HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    std::wstringstream details;
    details << WindowDetails(hwnd)
            << L" message=0x" << std::hex << message
            << L" wParam=0x" << static_cast<ULONG_PTR>(wParam)
            << L" lParam=0x" << static_cast<ULONG_PTR>(lParam)
            << L" low=" << std::dec << LOWORD(wParam)
            << L" high=" << HIWORD(wParam);
    DebugLog(source, details.str());
}

HWND ResolveTaskbarWindow(HWND hwnd) {
    // This is the visible AWT frame associated with the taskbar button. It must
    // remain the sole ThumbBar target: hidden JVM peers can make Explorer show
    // controls for one target but send THBN_CLICKED to another.
    return IsWindow(hwnd) ? hwnd : nullptr;
}

TaskbarState* FindState(HWND hwnd) {
    const auto it = g_states.find(hwnd);
    return it == g_states.end() ? nullptr : it->second.get();
}

TaskbarState* FindStateForOwnerThread() {
    const DWORD currentThreadId = GetCurrentThreadId();
    for (auto& [_, state] : g_states) {
        if (state->ownerThreadId == currentThreadId) return state.get();
    }
    return nullptr;
}

HICON LoadIconFile(const wchar_t* path) {
    if (path == nullptr || *path == L'\0') return nullptr;
    return static_cast<HICON>(LoadImageW(nullptr, path, IMAGE_ICON, 0, 0, LR_LOADFROMFILE));
}

void ReplaceIcon(HICON& target, const wchar_t* path) {
    HICON replacement = LoadIconFile(path);
    if (target != nullptr) DestroyIcon(target);
    target = replacement;
}

HRESULT EnsureTaskbar(TaskbarState& state) {
    if (state.taskbar != nullptr) return S_OK;
    const HRESULT created = CoCreateInstance(CLSID_TaskbarList, nullptr, CLSCTX_INPROC_SERVER,
        IID_PPV_ARGS(&state.taskbar));
    if (FAILED(created)) return created;
    const HRESULT initialized = state.taskbar->HrInit();
    if (FAILED(initialized)) {
        state.taskbar->Release();
        state.taskbar = nullptr;
    }
    return initialized;
}

void FillButton(THUMBBUTTON& button, UINT command, HICON icon, const wchar_t* tooltip) {
    button.dwMask = THB_ICON | THB_TOOLTIP | THB_FLAGS;
    button.iId = command;
    button.hIcon = icon;
    button.dwFlags = THBF_ENABLED;
    if (tooltip != nullptr) lstrcpynW(button.szTip, tooltip, ARRAYSIZE(button.szTip));
}

HRESULT ApplyButtons(HWND hwnd, TaskbarState& state) {
    const HRESULT apartment = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    const bool uninitializeApartment = SUCCEEDED(apartment);
    const HRESULT taskbarResult = EnsureTaskbar(state);
    if (FAILED(taskbarResult)) {
        if (uninitializeApartment) CoUninitialize();
        return taskbarResult;
    }

    THUMBBUTTON buttons[3]{};
    FillButton(buttons[0], kCommandPrevious, state.previousIcon, state.previousTooltip.c_str());
    FillButton(buttons[1], kCommandPlayPause, state.isPlaying ? state.pauseIcon : state.playIcon,
        (state.isPlaying ? state.pauseTooltip : state.playTooltip).c_str());
    FillButton(buttons[2], kCommandNext, state.nextIcon, state.nextTooltip.c_str());
    const bool updating = state.buttonsAdded;
    const HRESULT result = updating
        ? state.taskbar->ThumbBarUpdateButtons(hwnd, ARRAYSIZE(buttons), buttons)
        : state.taskbar->ThumbBarAddButtons(hwnd, ARRAYSIZE(buttons), buttons);
    if (SUCCEEDED(result)) state.buttonsAdded = true;
    std::wstringstream details;
    details << WindowDetails(hwnd) << L" operation=" << (updating ? L"update" : L"add")
            << L" hresult=0x" << std::hex << static_cast<unsigned long>(result);
    DebugLog(L"thumbbar-apply", details.str());
    if (uninitializeApartment) CoUninitialize();
    return result;
}

void ApplyButtonsOnOwnerThread(HWND hwnd, TaskbarState& state) {
    const HRESULT result = ApplyButtons(hwnd, state);
    if (SUCCEEDED(result)) KillTimer(hwnd, kRetryTimerId);
    else SetTimer(hwnd, kRetryTimerId, kRetryIntervalMillis, nullptr);
    if (state.callback != nullptr) {
        state.callback(SUCCEEDED(result) ? kActionButtonsApplied : kActionButtonsApplyFailed);
    }
}

int MediaActionForMessage(UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == WM_COMMAND) {
        switch (LOWORD(wParam)) {
            case kCommandPrevious: return kActionPrevious;
            case kCommandPlayPause: return kActionPlayPause;
            case kCommandNext: return kActionNext;
        }
    }
    if (message == WM_APPCOMMAND) {
        switch (GET_APPCOMMAND_LPARAM(lParam)) {
            case APPCOMMAND_MEDIA_PREVIOUSTRACK: return kActionPrevious;
            case APPCOMMAND_MEDIA_PLAY_PAUSE: return kActionPlayPause;
            case APPCOMMAND_MEDIA_NEXTTRACK: return kActionNext;
        }
    }
    return 0;
}

void HandleTaskbarMessage(HWND hwnd, TaskbarState& state, UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == g_taskbarButtonCreated || message == WM_COMMAND || message == WM_APPCOMMAND ||
        message == WM_SETTINGCHANGE) {
        DebugMessage(L"taskbar-message", hwnd, message, wParam, lParam);
    }
    if (message == g_taskbarButtonCreated) {
        state.buttonsAdded = false;
        ApplyButtonsOnOwnerThread(hwnd, state);
        return;
    }
    if (message == WM_SETTINGCHANGE) {
        if (state.callback != nullptr) state.callback(kActionThemeChanged);
        return;
    }
    const int action = MediaActionForMessage(message, wParam, lParam);
    if (action != 0 && state.callback != nullptr) {
        DebugLog(L"thumbbar-command", std::to_wstring(action));
        state.callback(action);
    }
}

LRESULT CALLBACK TaskbarSubclassProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam,
    UINT_PTR, DWORD_PTR) {
    if (FindState(hwnd) != nullptr &&
        (message == WM_COMMAND || message == WM_APPCOMMAND)) {
        // WH_CALLWNDPROC is the single authority for sent taskbar commands.
        // Keeping this subclass passive prevents the same THBN_CLICKED from
        // invoking the JVM callback a second time after the hook observed it.
        DebugMessage(L"subclass-observed", hwnd, message, wParam, lParam);
    }
    return DefSubclassProc(hwnd, message, wParam, lParam);
}

void InstallTaskbarSubclass(HWND hwnd, TaskbarState& state) {
    const bool installed = SetWindowSubclass(hwnd, TaskbarSubclassProc, kTaskbarSubclassId, 0) != FALSE;
    DebugLog(L"subclass", WindowDetails(hwnd) + L" installed=" + (installed ? L"1" : L"0"));
    if (state.callback != nullptr) {
        state.callback(installed ? kActionSubclassInstalled : kActionSubclassInstallFailed);
    }
}

LRESULT CALLBACK TaskbarCallWindowHook(int code, WPARAM wParam, LPARAM lParam) {
    if (code >= 0) {
        const auto* message = reinterpret_cast<const CWPSTRUCT*>(lParam);
        TaskbarState* state = FindState(message->hwnd);
        const int action = MediaActionForMessage(message->message, message->wParam, message->lParam);
        if (state == nullptr && action != 0) {
            // Explorer may send THBN_CLICKED to the Compose canvas (or another
            // peer) instead of the top-level frame used for ThumbBarAddButtons.
            // The command IDs are private to this bridge, so owner-thread routing
            // remains unambiguous while preserving the visible frame as the target.
            state = FindStateForOwnerThread();
            if (state != nullptr) {
                DebugMessage(L"foreign-window-command", message->hwnd, message->message,
                    message->wParam, message->lParam);
            }
        }
        if (state != nullptr) {
            HandleTaskbarMessage(state->hwnd, *state, message->message, message->wParam, message->lParam);
        }
    }
    return CallNextHookEx(nullptr, code, wParam, lParam);
}

LRESULT CALLBACK TaskbarGetMessageHook(int code, WPARAM wParam, LPARAM lParam) {
    if (code >= 0) {
        auto* message = reinterpret_cast<MSG*>(lParam);
        TaskbarState* state = FindState(message->hwnd);
        // Explorer can post THBN_CLICKED as a thread message. It then has no
        // target HWND, but remains on the taskbar window's owner thread. Keep
        // this fallback strict to our three IDs so unrelated AWT messages can
        // never be consumed.
        if (state == nullptr && MediaActionForMessage(message->message, message->wParam, message->lParam) != 0) {
            state = FindStateForOwnerThread();
            if (state != nullptr) {
                DebugMessage(message->hwnd == nullptr ? L"thread-command" : L"foreign-window-command",
                    message->hwnd, message->message, message->wParam, message->lParam);
                HandleTaskbarMessage(state->hwnd, *state, message->message, message->wParam, message->lParam);
                message->message = WM_NULL;
            }
        }
        if (state != nullptr && message->message != WM_NULL) {
            if (message->message == kInstallSubclassMessage) {
                InstallTaskbarSubclass(message->hwnd, *state);
                message->message = WM_NULL;
            } else if (message->message == kApplyButtonsMessage) {
                ApplyButtonsOnOwnerThread(message->hwnd, *state);
                message->message = WM_NULL;
            } else if (message->message == WM_TIMER && message->wParam == kRetryTimerId) {
                ApplyButtonsOnOwnerThread(message->hwnd, *state);
                message->message = WM_NULL;
            } else if (MediaActionForMessage(message->message, message->wParam, message->lParam) != 0) {
                HandleTaskbarMessage(message->hwnd, *state, message->message, message->wParam, message->lParam);
                message->message = WM_NULL;
            }
        }
    }
    return CallNextHookEx(nullptr, code, wParam, lParam);
}

bool InstallHooks(TaskbarState& state) {
    state.callWindowHook = SetWindowsHookExW(WH_CALLWNDPROC, TaskbarCallWindowHook, nullptr, state.ownerThreadId);
    if (state.callWindowHook == nullptr) return false;
    state.getMessageHook = SetWindowsHookExW(WH_GETMESSAGE, TaskbarGetMessageHook, nullptr, state.ownerThreadId);
    if (state.getMessageHook == nullptr) {
        UnhookWindowsHookEx(state.callWindowHook);
        state.callWindowHook = nullptr;
        return false;
    }
    return true;
}

bool UpdateState(HWND hwnd, const wchar_t* previous, const wchar_t* play, const wchar_t* pause,
    const wchar_t* next, const wchar_t* previousTooltip, const wchar_t* playTooltip,
    const wchar_t* pauseTooltip, const wchar_t* nextTooltip, bool isPlaying) {
    TaskbarState* state = FindState(hwnd);
    if (state == nullptr) return false;
    ReplaceIcon(state->previousIcon, previous);
    ReplaceIcon(state->playIcon, play);
    ReplaceIcon(state->pauseIcon, pause);
    ReplaceIcon(state->nextIcon, next);
    state->previousTooltip = previousTooltip == nullptr ? L"" : previousTooltip;
    state->playTooltip = playTooltip == nullptr ? L"" : playTooltip;
    state->pauseTooltip = pauseTooltip == nullptr ? L"" : pauseTooltip;
    state->nextTooltip = nextTooltip == nullptr ? L"" : nextTooltip;
    state->isPlaying = isPlaying;
    PostMessageW(hwnd, kApplyButtonsMessage, 0, 0);
    return true;
}

} // namespace

extern "C" __declspec(dllexport) void __stdcall lazer_taskbar_remove(HWND hwnd);
extern "C" __declspec(dllexport) int __stdcall lazer_taskbar_set_debug_log_path(const wchar_t* path);

extern "C" __declspec(dllexport) int __stdcall lazer_taskbar_set_debug_log_path(const wchar_t* path) {
    {
        std::lock_guard lock(g_debugLogMutex);
        g_debugLogPath = path == nullptr ? L"" : path;
    }
    if (path != nullptr && *path != L'\0') DebugLog(L"debug-enabled", L"pid=" + std::to_wstring(GetCurrentProcessId()));
    return 1;
}

extern "C" __declspec(dllexport) int __stdcall lazer_taskbar_install(HWND hwnd,
    const wchar_t* previous, const wchar_t* play, const wchar_t* pause, const wchar_t* next,
    const wchar_t* previousTooltip, const wchar_t* playTooltip, const wchar_t* pauseTooltip,
    const wchar_t* nextTooltip, int isPlaying, ActionCallback callback) {
    DebugLog(L"install-request", WindowDetails(hwnd));
    hwnd = ResolveTaskbarWindow(hwnd);
    if (hwnd == nullptr || !IsWindowVisible(hwnd)) return 0;
    if (g_taskbarButtonCreated == 0) g_taskbarButtonCreated = RegisterWindowMessageW(L"TaskbarButtonCreated");
    lazer_taskbar_remove(hwnd);

    auto state = std::make_unique<TaskbarState>();
    state->callback = callback;
    state->isPlaying = isPlaying != 0;
    state->hwnd = hwnd;
    state->ownerThreadId = GetWindowThreadProcessId(hwnd, nullptr);
    if (state->ownerThreadId == 0 || !InstallHooks(*state)) return 0;
    g_states.emplace(hwnd, std::move(state));
    DebugLog(L"install-target", WindowDetails(hwnd));
    PostMessageW(hwnd, kInstallSubclassMessage, 0, 0);
    return UpdateState(hwnd, previous, play, pause, next, previousTooltip, playTooltip,
        pauseTooltip, nextTooltip, isPlaying) ? 1 : 0;
}

extern "C" __declspec(dllexport) int __stdcall lazer_taskbar_update(HWND hwnd,
    const wchar_t* previous, const wchar_t* play, const wchar_t* pause, const wchar_t* next,
    const wchar_t* previousTooltip, const wchar_t* playTooltip, const wchar_t* pauseTooltip,
    const wchar_t* nextTooltip, int isPlaying) {
    hwnd = ResolveTaskbarWindow(hwnd);
    return UpdateState(hwnd, previous, play, pause, next, previousTooltip, playTooltip,
        pauseTooltip, nextTooltip, isPlaying) ? 1 : 0;
}

extern "C" __declspec(dllexport) void __stdcall lazer_taskbar_remove(HWND hwnd) {
    hwnd = ResolveTaskbarWindow(hwnd);
    if (hwnd == nullptr) return;
    const auto it = g_states.find(hwnd);
    if (it == g_states.end()) return;
    DebugLog(L"remove", WindowDetails(hwnd));
    KillTimer(hwnd, kRetryTimerId);
    g_states.erase(it);
}
