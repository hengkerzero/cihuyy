## 2024-03-24 - Upgraded standard EditText to TextInputLayout for Add Favorite Dialog
**Learning:** By switching to `TextInputLayout` paired with `TextInputEditText`, we significantly improved UX and accessibility. This structure provides a material-styled input field with a clear and distinct label/hint.
**Action:** Always prefer `TextInputLayout` over bare `EditText` for user inputs, particularly within dialogs or forms.

## 2024-03-24 - Accessibility improvements on core activities
**Learning:** Some elements in the UI, like the app icon on the "About" page, act purely as decorations but can capture the focus of screen readers, which adds clutter to the a11y experience. Adding `android:importantForAccessibility="no"` is an effective fix. In addition, providing proper descriptions via `app:navigationContentDescription` is vital for screen readers interacting with core navigation menus.
**Action:** When inspecting screens with decorative images, remember to enforce `importantForAccessibility="no"`. Make sure navigation icons always have a clear `contentDescription`.
