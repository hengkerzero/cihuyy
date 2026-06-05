## 2024-06-05 - Tooltips for Icon-Only Buttons
**Learning:** Tooltips on views significantly improve accessibility and discoverability for pointer input users and those using long-press interactions, especially for icon-only buttons where the purpose isn't explicitly clear from text alone. In standard layout XML, always use `android:tooltipText` instead of `app:tooltipText` which is only meant for MenuItems.
**Action:** Always add `android:tooltipText` to icon-only interactive elements (like `MaterialButton` or `ImageView`). Use the same string resource as `android:contentDescription`.
