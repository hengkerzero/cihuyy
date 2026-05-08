## 2026-05-08 - Add missing input hint
**Learning:** Found a missing placeholder (hint) in the Add Favorite dialog. It's a common accessibility and UX issue in Android XML layouts where `EditText` fields might not always have clear descriptions or hints out of the box.
**Action:** Always check `EditText` tags in dialog layouts for an `android:hint` or an associated `TextInputLayout` with a hint to ensure users know what to enter.
