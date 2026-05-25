## 2026-05-25 - Add tooltips to icon-only buttons
**Learning:** For Android Material components (like icon-only buttons), using `app:tooltipText` rather than `android:tooltipText` ensures comprehensive backwards compatibility. The tooltip should map to the same string resource as `android:contentDescription` to improve discoverability.
**Action:** Always verify that icon-only buttons have `android:contentDescription` for screen readers and `app:tooltipText` for mouse hover / long-press interactions, ensuring they point to the same descriptive string resource.
