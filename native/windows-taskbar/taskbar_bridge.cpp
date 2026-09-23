#include <windows.h>
#include <commctrl.h>
#include <shobjidl_core.h>

#include <memory>
#include <string>
#include <unordered_map>

namespace {

constexpr UINT kCommandPrevious = 0x1001;
constexpr UINT kCommandPlayPause = 0x1002;
constexpr UINT kCommandNext = 0x1003;
constexpr int kActionPrevious = 1;
constexpr int kActionPlayPause = 2;
constexpr int kActionNext = 3;
constexpr int kActionThemeChanged = 4;
constexpr int kActionButtonsApplied = 5;
constexpr int kActionButtonsApplyFailed = 6;
constexpr UINT_PTR kRetryTimerId = 0x4C617A66;
constexpr UINT kRetryIntervalMillis = 100;
constexpr UINT kApplyButtonsMessage = WM_APP + 0x4C61;

using ActionCallback = void(__stdcall*)(int action);

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
    HHOOK callWindowHook = nullptr;
    HHOOK getMessageHook = nullptr;
    bool isPlaying = false;
    bool buttonsAdded = false;

    ~TaskbarState() {
        if (taskbar != nullptr) taskbar->Release();
        if (previousIcon != nullptr) DestroyIcon(previousIcon);
        if (playIcon != nullptr) DestroyIcon(playIcon);
        if (pauseIcon != nullptr) DestroyIcon(pauseIcon);
        if (nextIcon != nullptr) DestroyIcon(nextIcon);
    }
};

std::unordered_map<HWND, std::unique_ptr<TaskbarState>> g_states;
UINT g_taskbarButtonCreated = 0;

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
    button.dwFlags = THBF_ENABLED | THBF_DISMISSONCLICK;
    if (tooltip != nullptr) lstrcpynW(button.szTip, tooltip, ARRAYSIZE(button.szTip));
}

HRESULT ApplyButtons(HWND hwnd, TaskbarState& state) {
    const HRESULT taskbarResult = EnsureTaskbar(state);
    if (FAILED(taskbarResult)) return taskbarResult;

    THUMBBUTTON buttons[3]{};
    FillButton(buttons[0], kCommandPrevious, state.previousIcon, state.previousTooltip.c_str());
    FillButton(buttons[1], kCommandPlayPause, state.isPlaying ? state.pauseIcon : state.playIcon,
        (state.isPlaying ? state.pauseTooltip : state.playTooltip).c_str());
    FillButton(buttons[2], kCommandNext, state.nextIcon, state.nextTooltip.c_str());

    const HRESULT result = state.buttonsAdded
        ? state.taskbar->ThumbBarUpdateButtons(hwnd, ARRAYSIZE(buttons), buttons)
        : state.taskbar->ThumbBarAddButtons(hwnd, ARRAYSIZE(buttons), buttons);
    if (SUCCEEDED(result)) state.buttonsAdded = true;
    return result;
}

void RequestButtonApply(HWND hwnd) {
    PostMessageW(hwnd, kApplyButtonsMessage, 0, 0);
}

void ApplyButtonsOnOwnerThread(HWND hwnd, TaskbarState& state) {
    const HRESULT result = ApplyButtons(hwnd, state);
    if (SUCCEEDED(result)) KillTimer(hwnd, kRetryTimerId);
    else SetTimer(hwnd, kRetryTimerId, kRetryIntervalMillis, nullptr);
    if (state.callback != nullptr) {
        state.callback(SUCCEEDED(result) ? kActionButtonsApplied : kActionButtonsApplyFailed);
    }
}

void HandleTaskbarMessage(HWND hwnd, TaskbarState& state, UINT message, WPARAM wParam) {
    if (message == g_taskbarButtonCreated) {
        state.buttonsAdded = false;
        ApplyButtonsOnOwnerThread(hwnd, state);
        return;
    }
    if (message == WM_TIMER && wParam == kRetryTimerId) {
        // Compose can create its AWT peer after Explorer already emitted TaskbarButtonCreated.
        // Keep retrying the documented AddButtons call from this window's own message thread
        // until Explorer has a taskbar button for the window.
        ApplyButtonsOnOwnerThread(hwnd, state);
        return;
    }
    if (message == kApplyButtonsMessage) {
        ApplyButtonsOnOwnerThread(hwnd, state);
        return;
    }
    if (message == WM_SETTINGCHANGE) {
        if (state.callback != nullptr) state.callback(kActionThemeChanged);
        return;
    }
    if (message == WM_COMMAND) {
        const UINT command = LOWORD(wParam);
        int action = 0;
        if (command == kCommandPrevious) action = kActionPrevious;
        if (command == kCommandPlayPause) action = kActionPlayPause;
        if (command == kCommandNext) action = kActionNext;
        if (action != 0) {
            // Explorer normally documents THBN_CLICKED in HIWORD(wParam), but this can arrive
            // as zero through the AWT peer's native window procedure. The command IDs belong
            // exclusively to this bridge, so they are the reliable discriminator here.
            if (state.callback != nullptr) state.callback(action);
            return;
        }
    }
}

// Compose/Skiko owns and can replace its AWT window procedure after the Kotlin side has obtained
// the HWND. A thread hook observes messages before that procedure runs, so thumbnail commands
// remain visible even when Skiko changes the procedure later in the window lifetime.
LRESULT CALLBACK TaskbarMessageHook(int code, WPARAM wParam, LPARAM lParam) {
    if (code >= 0) {
        const auto* message = reinterpret_cast<const CWPSTRUCT*>(lParam);
        const bool shouldHandle = message->message == g_taskbarButtonCreated ||
            message->message == WM_SETTINGCHANGE || message->message == WM_COMMAND;
        if (shouldHandle) {
            const auto it = g_states.find(message->hwnd);
            if (it != g_states.end()) {
                HandleTaskbarMessage(message->hwnd, *it->second, message->message, message->wParam);
            }
        }
    }
    return CallNextHookEx(nullptr, code, wParam, lParam);
}

// Posted WM_APP messages and WM_TIMER messages are retrieved from the AWT message queue before
// they reach a window procedure. Use this hook for taskbar registration/retry work; Explorer's
// sent WM_COMMAND notifications remain handled by TaskbarMessageHook above.
LRESULT CALLBACK TaskbarGetMessageHook(int code, WPARAM wParam, LPARAM lParam) {
    if (code >= 0) {
        const auto* message = reinterpret_cast<const MSG*>(lParam);
        const bool isApplyMessage = message->message == kApplyButtonsMessage;
        const bool isRetryTimer = message->message == WM_TIMER && message->wParam == kRetryTimerId;
        if (isApplyMessage || isRetryTimer) {
            const auto it = g_states.find(message->hwnd);
            if (it != g_states.end()) {
                HandleTaskbarMessage(message->hwnd, *it->second, message->message, message->wParam);
            }
        }
    }
    return CallNextHookEx(nullptr, code, wParam, lParam);
}

bool UpdateState(HWND hwnd, const wchar_t* previous, const wchar_t* play, const wchar_t* pause,
    const wchar_t* next, const wchar_t* previousTooltip, const wchar_t* playTooltip,
    const wchar_t* pauseTooltip, const wchar_t* nextTooltip, bool isPlaying) {
    const auto it = g_states.find(hwnd);
    if (it == g_states.end()) return false;
    TaskbarState& state = *it->second;
    ReplaceIcon(state.previousIcon, previous);
    ReplaceIcon(state.playIcon, play);
    ReplaceIcon(state.pauseIcon, pause);
    ReplaceIcon(state.nextIcon, next);
    state.previousTooltip = previousTooltip == nullptr ? L"" : previousTooltip;
    state.playTooltip = playTooltip == nullptr ? L"" : playTooltip;
    state.pauseTooltip = pauseTooltip == nullptr ? L"" : pauseTooltip;
    state.nextTooltip = nextTooltip == nullptr ? L"" : nextTooltip;
    state.isPlaying = isPlaying;
    RequestButtonApply(hwnd);
    return true;
}

} // namespace

extern "C" __declspec(dllexport) void __stdcall lazer_taskbar_remove(HWND hwnd);

extern "C" __declspec(dllexport) int __stdcall lazer_taskbar_install(HWND hwnd,
    const wchar_t* previous, const wchar_t* play, const wchar_t* pause, const wchar_t* next,
    const wchar_t* previousTooltip, const wchar_t* playTooltip, const wchar_t* pauseTooltip,
    const wchar_t* nextTooltip, int isPlaying, ActionCallback callback) {
    if (hwnd == nullptr) return 0;
    if (g_taskbarButtonCreated == 0) g_taskbarButtonCreated = RegisterWindowMessageW(L"TaskbarButtonCreated");
    lazer_taskbar_remove(hwnd);

    auto state = std::make_unique<TaskbarState>();
    state->callback = callback;
    state->isPlaying = isPlaying != 0;
    g_states.emplace(hwnd, std::move(state));
    // The Compose call is on AWT-EventQueue-0, but the HWND belongs to AWT-Windows. A
    // thread-specific call-window hook executes in that owner thread without replacing Skiko's
    // procedure, and also provides the required COM apartment for ITaskbarList3 calls.
    const DWORD ownerThread = GetWindowThreadProcessId(hwnd, nullptr);
    const HHOOK callWindowHook = SetWindowsHookExW(
        WH_CALLWNDPROC, TaskbarMessageHook, nullptr, ownerThread);
    if (callWindowHook == nullptr) {
        g_states.erase(hwnd);
        return 0;
    }
    const HHOOK getMessageHook = SetWindowsHookExW(
        WH_GETMESSAGE, TaskbarGetMessageHook, nullptr, ownerThread);
    if (getMessageHook == nullptr) {
        UnhookWindowsHookEx(callWindowHook);
        g_states.erase(hwnd);
        return 0;
    }
    g_states.at(hwnd)->callWindowHook = callWindowHook;
    g_states.at(hwnd)->getMessageHook = getMessageHook;
    // Keep the callback alive while the owner thread receives the posted apply request.
    UpdateState(hwnd, previous, play, pause, next, previousTooltip, playTooltip,
        pauseTooltip, nextTooltip, isPlaying);
    return 1;
}

extern "C" __declspec(dllexport) int __stdcall lazer_taskbar_update(HWND hwnd,
    const wchar_t* previous, const wchar_t* play, const wchar_t* pause, const wchar_t* next,
    const wchar_t* previousTooltip, const wchar_t* playTooltip, const wchar_t* pauseTooltip,
    const wchar_t* nextTooltip, int isPlaying) {
    return UpdateState(hwnd, previous, play, pause, next, previousTooltip, playTooltip,
        pauseTooltip, nextTooltip, isPlaying) ? 1 : 0;
}

extern "C" __declspec(dllexport) void __stdcall lazer_taskbar_remove(HWND hwnd) {
    if (hwnd == nullptr) return;
    KillTimer(hwnd, kRetryTimerId);
    const auto it = g_states.find(hwnd);
    if (it == g_states.end()) return;
    if (it->second->callWindowHook != nullptr) UnhookWindowsHookEx(it->second->callWindowHook);
    if (it->second->getMessageHook != nullptr) UnhookWindowsHookEx(it->second->getMessageHook);
    g_states.erase(it);
}
