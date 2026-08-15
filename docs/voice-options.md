# Voice cost options (checked 15 August 2026)

There is no trustworthy permanently free public phone number. The lowest-cost
architecture is to use browser or SIP app-to-app calling normally, and rent a
PSTN number only when people must call from the public telephone network.

| Path | Fixed cost | Usage cost | Fit |
|---|---:|---:|---|
| ASTRA browser | ₹0 | ₹0 telephony | Best Mac experience; uses internet only |
| Local Android SIP + Asterisk | ₹0 | ₹0 telephony | Best personal phone/watch path on the same LAN/VPN |
| Linphone community SIP | ₹0 | ₹0 app-to-app | Free SIP address, no SLA, cannot attach a virtual number |
| Plivo India | ₹250/month | ₹0.60/min local inbound/outbound | Cheapest published India-native programmable path found; business KYC required |
| Twilio US local | $1.15/month | $0.0085/min inbound + $0.0044/min Media Streams | Implemented international WebSocket path; broad number inventory |
| Telnyx US/global | numbers start near $1/month | SIP inbound from $0.0032/min; WebSocket streaming may add a platform fee | Usually the cheapest public-number candidate, but needs a Telnyx call-control adapter |
| Exotel trial | ₹0 temporary | ₹1,000 trial credits/15 days | Evaluation only; full pricing is sales/plan dependent |

Provider pricing and regulatory availability change by destination and account.
Check the linked live tables before buying:

- [Gemini API pricing](https://ai.google.dev/gemini-api/docs/pricing)
- [Gemini rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)
- [Plivo India voice pricing](https://www.plivo.com/voice/pricing/in/)
- [Twilio US voice pricing](https://www.twilio.com/en-us/voice/pricing/us)
- [Telnyx SIP pricing](https://telnyx.com/pricing/elastic-sip)
- [Exotel trial terms](https://docs.exotel.com/business-phone-system/create-a-trial-account)
- [Linphone free SIP service](https://www.linphone.org/en/getting-started/)

## Recommendation

Use the browser and local SIP app as the default free surfaces. Keep Plivo only
if an Indian DID is essential. Use the implemented Twilio socket for a quick
non-Indian number; add Telnyx only if measured call volume makes its lower rate
worth another provider adapter. Do not use Vapi/Retell/hosted voice-agent
orchestration here—the direct Gemini Live path already supplies VAD, STT/TTS,
barge-in, and tools, so those services add cost and latency.
