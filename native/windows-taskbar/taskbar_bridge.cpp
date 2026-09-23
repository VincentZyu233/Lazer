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
constexpr int kActionSubclassInstalled = 7;
constexpr int kActionSubclassInstallFailed = 8;
constexpr int kActionCallWindowCommandObserved = 0x10000000;
constexpr int kActionQueuedCommandObserved = 0x20000000;
constexpr int kActionSubclassCommandObserved = 0x30000000;
constexpr UINT_PTR kRetryTimerId = 0x4C617A66;
constexpr UINT kRetryIntervalMillis = 100;
constexpr UINT kApplyButtonsMessage = WM_APP + 0x4C61;
constexpr UINT_PTR kTaskbarSubclassId = 0x4C617A72;

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
    DWORD ownerThread = 0;
    bool subclassInstalled = false;
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

HWND ResolveTaskbarWindow(HWND hwnd) {
    if (hwnd == nullptr) return nullptr;
    // Native.getWindowPointer can expose a Compose rendering child. The shell owns thumbnail
    // buttons on the taskbar-visible root owner and sends its commands to that top-level peer.
    const HWND rootOwner = GetAncestor(hwnd, GA_ROOTOWNER);
    return rootOwner == nullptr ? hwnd : rootOwner;
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

int MediaActionForMessage(UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == WM_COMMAND) {
        const UINT command = LOWORD(wParam);
        if (command == kCommandPrevious) return kActionPrevious;
        if (command == kCommandPlayPause) return kActionPlayPause;
        if (command == kCommandNext) return kActionNext;
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

bool IsTaskbarMediaMessage(UINT message, WPARAM wParam, LPARAM lParam) {
    return MediaActionForMessage(message, wParam, lParam) != 0;
}

// The shell can deliver a thumbnail WM_COMMAND to the taskbar-visible parent/proxy HWND rather
// than the Compose child HWND passed to ThumbBarAddButtons. The owner thread and private command
// IDs are stable across those peer windows, so use them to recover the registered taskbar state.
TaskbarState* FindStateForCommand(HWND& hwnd) {
    const DWORD currentThread = GetCurrentThreadId();
    for (auto& [stateHwnd, state] : g_states) {
        if (state->ownerThread == currentThread) {
            hwnd = stateHwnd;
            return state.get();
        }
    }
    return nullptr;
}

void ReportObservedCommand(TaskbarState* state, int source, WPARAM wParam) {
    if (state != nullptr && state->callback != nullptr) {
        state->callback(source | static_cast<int>(LOWORD(wParam)));
    }
}

void ApplyButtonsOnOwnerThread(HWND hwnd, TaskbarState& state) {
    const HRESULT result = ApplyButtons(hwnd, state);
    if (SUCCEEDED(result)) KillTimer(hwnd, kRetryTimerId);
    else SetTimer(hwnd, kRetryTimerId, kRetryIntervalMillis, nullptr);
    if (state.callback != nullptr) {
        state.callback(SUCCEEDED(result) ? kActionButtonsApplied : kActionButtonsApplyFailed);
    }
}

void HandleTaskbarMessage(HWND hwnd, TaskbarState& state, UINT message, WPARAM wParam, LPARAM lParam) {
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
    const int action = MediaActionForMessage(message, wParam, lParam);
    if (action != 0) {
        // Explorer normally documents THBN_CLICKED in HIWORD(wParam), but this can arrive as
        // zero through the AWT peer. Some Shell routes instead expose media APPCOMMAND values.
        if (state.callback != nullptr) state.callback(action);
    }
}

// This is installed by TaskbarGetMessageHook, which runs on the HWND creator thread. Unlike a
// cross-thread SetWindowLongPtr replacement, the common-controls subclass chain is compatible
// with AWT/Skiko owning the underlying procedure.
LRESULT CALLBACK TaskbarSubclassProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam,
    UINT_PTR, DWORD_PTR) {
    const auto it = g_states.find(hwnd);
    if (it != g_states.end()) {
        if (message == WM_COMMAND) {
            ReportObservedCommand(it->second.get(), kActionSubclassCommandObserved, wParam);
        }
        HandleTaskbarMessage(hwnd, *it->second, message, wParam, lParam);
    }
    return DefSubclassProc(hwnd, message, wParam, lParam);
}

void EnsureTaskbarSubclass(HWND hwnd, TaskbarState& state) {
    if (state.subclassInstalled) return;
    state.subclassInstalled = SetWindowSubclass(
        hwnd, TaskbarSubclassProc, kTaskbarSubclassId, 0) != FALSE;
    if (state.callback != nullptr) {
        state.callback(state.subclassInstalled ? kActionSubclassInstalled : kActionSubclassInstallFailed);
    }
}

// Compose/Skiko owns and can replace its AWT window procedure after the Kotlin side has obtained
// the HWND. A thread hook observes messages before that procedure runs, so thumbnail commands
// remain visible even when Skiko changes the procedure later in the window lifetime.
LRESULT CALLBACK TaskbarMessageHook(int code, WPARAM wParam, LPARAM lParam) {
    if (code >= 0) {
        const auto* message = reinterpret_cast<const CWPSTRUCT*>(lParam);
        if (message->message == WM_COMMAND) {
            const auto stateIt = g_states.find(message->hwnd);
            if (stateIt != g_states.end()) {
                ReportObservedCommand(stateIt->second.get(), kActionCallWindowCommandObserved, message->wParam);
            } else {
                HWND stateHwnd = nullptr;
                ReportObservedCommand(FindStateForCommand(stateHwnd), kActionCallWindowCommandObserved, message->wParam);
            }
        }
        const bool shouldHandle = message->message == g_taskbarButtonCreated ||
            message->message == WM_SETTINGCHANGE || message->message == WM_COMMAND ||
            message->message == WM_APPCOMMAND;
        if (shouldHandle) {
            const auto it = g_states.find(message->hwnd);
            if (it != g_states.end()) {
                HandleTaskbarMessage(message->hwnd, *it->second, message->message, message->wParam, message->lParam);
            } else if (IsTaskbarMediaMessage(message->message, message->wParam, message->lParam)) {
                HWND stateHwnd = nullptr;
                if (TaskbarState* state = FindStateForCommand(stateHwnd); state != nullptr) {
                    HandleTaskbarMessage(stateHwnd, *state, message->message, message->wParam, message->lParam);
                }
            }
        }
    }
    return CallNextHookEx(nullptr, code, wParam, lParam);
}

// Posted WM_APP messages, WM_TIMER messages, and some Explorer WM_COMMAND notifications are
// retrieved from the AWT queue before they reach a window procedure. The call-window hook above
// covers sent commands; this hook covers queued commands and consumes only our private IDs.
LRESULT CALLBACK TaskbarGetMessageHook(int code, WPARAM wParam, LPARAM lParam) {
    if (code >= 0) {
        auto* message = reinterpret_cast<MSG*>(lParam);
        if (message->message == WM_COMMAND) {
            const auto stateIt = g_states.find(message->hwnd);
            if (stateIt != g_states.end()) {
                ReportObservedCommand(stateIt->second.get(), kActionQueuedCommandObserved, message->wParam);
            } else {
                HWND stateHwnd = nullptr;
                ReportObservedCommand(FindStateForCommand(stateHwnd), kActionQueuedCommandObserved, message->wParam);
            }
        }
        const bool isApplyMessage = message->message == kApplyButtonsMessage;
        const bool isRetryTimer = message->message == WM_TIMER && message->wParam == kRetryTimerId;
        const bool isTaskbarCommand = IsTaskbarMediaMessage(message->message, message->wParam, message->lParam);
        if (isApplyMessage || isRetryTimer || isTaskbarCommand) {
            const auto it = g_states.find(message->hwnd);
            if (it != g_states.end()) {
                if (isApplyMessage) EnsureTaskbarSubclass(message->hwnd, *it->second);
                HandleTaskbarMessage(message->hwnd, *it->second, message->message, message->wParam, message->lParam);
                // Our registration/timer messages and private command IDs have no meaning to
                // AWT. Prevent a queued thumbnail command from being dispatched a second time.
                message->message = WM_NULL;
            } else if (isTaskbarCommand) {
                HWND stateHwnd = nullptr;
                if (TaskbarState* state = FindStateForCommand(stateHwnd); state != nullptr) {
                    HandleTaskbarMessage(stateHwnd, *state, message->message, message->wParam, message->lParam);
                    message->message = WM_NULL;
                }
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
    hwnd = ResolveTaskbarWindow(hwnd);
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
    g_states.at(hwnd)->ownerThread = ownerThread;
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
    return UpdateState(ResolveTaskbarWindow(hwnd), previous, play, pause, next, previousTooltip, playTooltip,
        pauseTooltip, nextTooltip, isPlaying) ? 1 : 0;
}

extern "C" __declspec(dllexport) void __stdcall lazer_taskbar_remove(HWND hwnd) {
    hwnd = ResolveTaskbarWindow(hwnd);
    if (hwnd == nullptr) return;
    KillTimer(hwnd, kRetryTimerId);
    const auto it = g_states.find(hwnd);
    if (it == g_states.end()) return;
    if (it->second->callWindowHook != nullptr) UnhookWindowsHookEx(it->second->callWindowHook);
    if (it->second->getMessageHook != nullptr) UnhookWindowsHookEx(it->second->getMessageHook);
    g_states.erase(it);
}
