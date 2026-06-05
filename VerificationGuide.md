# DNS Client Blocking — Manual Verification Checklist

Use this guide after deploying NetGuard Pulse with DNS-based hotspot client blocking enabled.

## STEP 1 — Basic DNS Interception

- [ ] Connect a laptop to the Android hotspot
- [ ] Run: `nslookup google.com 192.168.43.1`
- [ ] Expected: returns real IP (not spoofed) for non-blocked clients

## STEP 2 — Block a Client

- [ ] Trigger `blockClient()` for the laptop's IP via app UI
- [ ] Run: `nslookup google.com 192.168.43.1`
- [ ] Expected: returns `192.168.43.1` (spoofed)

## STEP 3 — Captive Portal Page

- [ ] Open browser on blocked laptop
- [ ] Navigate to any HTTP site (e.g. `http://example.com`)
- [ ] Expected: "Data Limit Reached" page appears

## STEP 4 — Unblock

- [ ] Trigger `unblockClient()` via app UI
- [ ] Run: `nslookup google.com 192.168.43.1`
- [ ] Expected: real IP returned again, browsing works

## STEP 5 — Automatic Limit Trigger

- [ ] Set a very low data limit (e.g. 1 MB) for a client
- [ ] Browse on client device until limit exceeded
- [ ] Expected: automatic redirect to captive portal page

## STEP 6 — Other Clients Unaffected

- [ ] Connect two devices; block only one
- [ ] Verify blocked device sees portal, other browses normally

## Notes

- DNS interception binds to the hotspot gateway (`192.168.43.1:53`) and the captive portal serves HTTP on port 80.
- If port 53 bind fails (already in use), check `blockingState` in the app for an error message.
- HTTPS sites will show certificate errors when blocked; HTTP sites show the limit page directly.
