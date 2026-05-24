## 2024-05-24 - Tooltips for icon-only buttons
**Learning:** Icon-only buttons (like MaterialButton) must have `app:tooltipText` matching `android:contentDescription` to ensure tooltips appear on long-press, improving accessibility and discoverability. Using `app:tooltipText` rather than `android:tooltipText` ensures comprehensive backwards compatibility on older Android SDKs.
**Action:** Always add `app:tooltipText` to icon-only buttons, mapping it to the same string resource as `android:contentDescription`.
