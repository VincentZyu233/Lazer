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
    WNDPROC previousWindowProc = nullptr;
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

LRESULT CALLBACK TaskbarWindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    const auto it = g_states.find(hwnd);
    if (it == g_states.end()) return DefWindowProcW(hwnd, message, wParam, lParam);
    TaskbarState& state = *it->second;

    if (message == g_taskbarButtonCreated) {
        state.buttonsAdded = false;
        if (SUCCEEDED(ApplyButtons(hwnd, state))) KillTimer(hwnd, kRetryTimerId);
        return CallWindowProcW(state.previousWindowProc, hwnd, message, wParam, lParam);
    }
    if (message == WM_TIMER && wParam == kRetryTimerId) {
        // Compose can create its AWT peer after Explorer already emitted TaskbarButtonCreated.
        // Keep retrying the documented AddButtons call from this window's own message thread
        // until Explorer has a taskbar button for the window.
        if (SUCCEEDED(ApplyButtons(hwnd, state))) KillTimer(hwnd, kRetryTimerId);
        return 0;
    }
    if (message == kApplyButtonsMessage) {
        if (SUCCEEDED(ApplyButtons(hwnd, state))) KillTimer(hwnd, kRetryTimerId);
        else SetTimer(hwnd, kRetryTimerId, kRetryIntervalMillis, nullptr);
        return 0;
    }
    if (message == WM_SETTINGCHANGE) {
        if (state.callback != nullptr) state.callback(kActionThemeChanged);
        return CallWindowProcW(state.previousWindowProc, hwnd, message, wParam, lParam);
    }
    if (message == WM_COMMAND && HIWORD(wParam) == THBN_CLICKED) {
        const UINT command = LOWORD(wParam);
        int action = 0;
        if (command == kCommandPrevious) action = kActionPrevious;
        if (command == kCommandPlayPause) action = kActionPlayPause;
        if (command == kCommandNext) action = kActionNext;
        if (action != 0) {
            if (state.callback != nullptr) state.callback(action);
            return 0;
        }
    }
    return CallWindowProcW(state.previousWindowProc, hwnd, message, wParam, lParam);
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
    // SetWindowSubclass may only be called from the HWND's creator thread. Compose invokes this
    // entry point from AWT-EventQueue-0 while the native peer is owned by AWT-Windows, so install
    // a native procedure directly and marshal taskbar COM work back with PostMessage.
    SetLastError(0);
    const auto previousWindowProc = reinterpret_cast<WNDPROC>(
        SetWindowLongPtrW(hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(TaskbarWindowProc)));
    if (previousWindowProc == nullptr && GetLastError() != 0) {
        g_states.erase(hwnd);
        return 0;
    }
    g_states.at(hwnd)->previousWindowProc = previousWindowProc;
    // Report success once the procedure is active so Kotlin keeps the bridge and its callback
    // alive while the owner thread receives the posted apply request.
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
    const WNDPROC previousWindowProc = it->second->previousWindowProc;
    const auto currentWindowProc = reinterpret_cast<WNDPROC>(GetWindowLongPtrW(hwnd, GWLP_WNDPROC));
    if (currentWindowProc == TaskbarWindowProc && previousWindowProc != nullptr) {
        SetWindowLongPtrW(hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(previousWindowProc));
    }
    g_states.erase(it);
}
