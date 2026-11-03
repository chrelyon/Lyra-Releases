param([switch]$Clear)
# Do not destroy previous diagnostic events on startup.
if ($Clear) { adb logcat -c }
adb logcat -v time LYRA_AUTO_PLAYLIST:D LYRA_LIBRARY:D LYRA_AUTO:D LYRA_PLAYER:D LYRA_STREAM:D LYRA_QUEUE:D LYRA_AUTH:D LYRA_RESTORE:D LYRA_EXTERNAL:D AndroidRuntime:E '*:S'
