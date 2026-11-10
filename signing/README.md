# Sideload signing key

`sideload.jks` is a **public** key (password `masar-sideload`, alias `masar`).
It exists so every build has the same signature and installs over the previous
one with `adb install -r`. It proves nothing about who built an APK.

If you fork this and distribute builds, use your own key instead: set the
repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
`KEY_PASSWORD`, and the workflow signs with those.
