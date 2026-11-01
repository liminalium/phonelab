# Prototype signing key

`prototype.p12` (password `prototype`) is committed on purpose. Every CI build is
signed with it, so each new APK installs over the previous one.

Because the key is public, anyone could sign an APK that installs over yours.
That is fine while you only install builds from this repo's Releases. Before the
app is shared with anyone else, switch to a private release key kept in GitHub
Secrets. The switch needs one uninstall, since Android refuses an update signed
with a different key.
