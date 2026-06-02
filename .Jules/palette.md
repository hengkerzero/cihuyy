## 2026-06-02 - [Tooltips for Icon-only Buttons]
**Learning:** For icon-only Material buttons in Android (minSdk 27), `android:contentDescription` alone is insufficient for visual users. Pairing it with `app:tooltipText` ensures comprehensive accessibility by providing both screen reader support and visual tooltips on long-press or hover.
**Action:** Always use `app:tooltipText` (not `android:tooltipText` for backward compatibility) on icon-only Material components, mapped to the same string resource as `android:contentDescription`.
