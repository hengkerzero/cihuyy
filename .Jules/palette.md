## 2026-05-22 - Add Tooltips to Icon-Only Elements
**Learning:** For Android apps supporting API 26+, adding `android:tooltipText` matching `android:contentDescription` on icon-only interactive elements is a reusable pattern that improves discoverability, matching standard accessibility practices while providing visual tooltips on long press or mouse hover.
**Action:** Always check `ImageView`, `ImageButton`, and `MaterialButton` (with `app:icon`) that lack text labels, ensuring they have both `android:contentDescription` for screen readers and `android:tooltipText` for sighted users.
