## $(date +%Y-%m-%d) - Adding Tooltips to Icon-Only Buttons
**Learning:** Native `android:tooltipText` acts as a solid accessibility fallback for icon-only buttons (`MaterialButton` or clickable `ImageView`) on Android APIs 26+. It leverages `android:contentDescription` strings easily. It also avoids needing custom tooltip code.
**Action:** When inspecting Android XML layout for accessibility, look for `ImageView`, `ImageButton`, and `MaterialButton` elements with `app:icon` and no visible text. Add `android:tooltipText` sharing the same string resource as `android:contentDescription`.
