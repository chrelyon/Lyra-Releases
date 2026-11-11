package app.lyra.music.tokenlinktest
import app.lyra.music.access.TokenActivationLink
fun main() {
 val token="lyra_"+"A".repeat(48)
 val base="https://vivisochat-lab.tail056f21.ts.net"
 check(TokenActivationLink.parse("$base:10000/app/activate#token=$token")==token)
 check(TokenActivationLink.parse("$base/app/activate#token=$token")==token)
 check(TokenActivationLink.parse("lyra://activate?token=$token")==token)
 println("PASS verified HTTPS links and explicit app handoff import the exact token draft")
 for (link in listOf(
  "http://vivisochat-lab.tail056f21.ts.net/app/activate#token=$token",
  "https://attacker.invalid/app/activate#token=$token",
  "https://attacker@vivisochat-lab.tail056f21.ts.net/app/activate#token=$token",
  "$base:9000/app/activate#token=$token",
  "$base/app/activate?token=$token",
  "$base/app/activate#token=$token&token=$token",
  "$base/app/activate#token=$token&server=https://attacker.invalid",
  "$base/app/activate#token=old_manual_master_token",
  "$base/app/activate#token=lyra_short",
  "lyra://other?token=$token", "lyra://activate?token=$token&save=true",
  "$base/app/activate#token="+"A".repeat(2000))) {
  check(TokenActivationLink.parse(link)==null) { "Invalid activation link accepted" }
 }
 check(TokenActivationLink.parse(null)==null)
 println("PASS foreign origins, HTTP, duplicate fields, endpoint overrides, old tokens and automatic-save directives rejected")
}
