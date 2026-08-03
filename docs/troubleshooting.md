# Beacon — Troubleshooting

## A Tag shows as "offline"

A Tag is marked **offline** when Beacon has received no ping from it for more
than 15 minutes. The usual causes, in order of how often we see them:

1. **Vehicle is parked indoors** — GPS can't get a fix in a covered garage. This
   is expected; the Tag will reconnect within a few minutes of driving out.
2. **Tag has no cellular signal** — Beacon Tags report over the cellular network,
   not the vehicle's Wi-Fi. In a dead zone the Tag buffers up to 2 hours of pings
   locally and uploads them in a burst once signal returns. Gaps then backfill.
3. **Blown Tag fuse** — a Tag draws power from the vehicle's OBD-II port. If the
   port's fuse is blown the Tag is dead until the fuse is replaced. Check whether
   other OBD accessories in the same vehicle also lost power.
4. **Deactivated in billing** — a Tag moved to "pending" because you are over your
   plan's Max Tags cap will show as offline on the map. This is a billing state,
   not a hardware fault — see the Pricing doc.

If none of these apply and the Tag has been silent over 2 hours, power-cycle it
by unplugging it from the OBD-II port for 30 seconds and reinserting.

## Idle alerts firing constantly

Idle alerts fire when a moving vehicle stops longer than the fleet's idle
threshold (default 10 minutes). If drivers legitimately idle at depots, raise
the threshold per fleet in **Console → Fleet → Settings → Idle threshold**.
Note the threshold is per fleet, not per Tag, so all vehicles in a fleet share it.

## Webhook events not arriving

Webhooks are a Pro and Enterprise feature; on Starter they are silently dropped.
On Pro or above, Beacon retries a failing webhook 3 times with exponential
backoff, then gives up — it does not queue events indefinitely. A webhook
endpoint that is down for more than ~10 minutes will miss events permanently.
