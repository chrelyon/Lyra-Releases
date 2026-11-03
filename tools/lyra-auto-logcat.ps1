param([switch]$Clear)
# Preserve existing Logcat evidence unless the operator explicitly chooses -Clear.
if ($Clear) { adb logcat -c }
adb logcat -v time LYRA_AUTO_PLAYLIST:D LYRA_LIBRARY:D LYRA_AUTO:D LYRA_QUEUE:D LYRA_EXTERNAL:D LYRA_PLAYER:D LYRA_STREAM:D LYRA_RESTORE:D LYRA_AUTH:D AndroidRuntime:E '*:S'
