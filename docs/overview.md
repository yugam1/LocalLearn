# Beacon — Product Overview

Beacon is Aldritch Logistics' fleet-tracking platform. It ingests GPS pings from
in-vehicle Beacon Tags and shows live location, route history, and idle-time
alerts in the Beacon web console.

## Core concepts

- **Beacon Tag** — the hardware device bolted into each vehicle. Each Tag has a
  12-character serial (format `BT-XXXXXXXXX`) printed on the label and reports a
  GPS ping every 30 seconds when the vehicle is moving, and every 5 minutes when
  it is stationary. This cadence is fixed and cannot be changed by the customer.
- **Fleet** — a named group of Tags. A Beacon account can hold up to 50 fleets.
- **Geofence** — a polygon drawn on the map. When a Tag enters or leaves a
  geofence, Beacon fires an event you can route to email or a webhook.
- **Idle alert** — triggers when a moving vehicle stops for longer than the
  fleet's idle threshold (default 10 minutes, configurable per fleet).

## Data retention

Live location is retained for 90 days on all plans. Route history older than 90
days is downsampled to one point per 5 minutes and kept for the length of your
plan's retention window (see the Pricing doc). Deleted Tags stop reporting
immediately but their historical pings remain until the retention window lapses.

## The console

The Beacon web console lives at `console.beacon.aldritch.example`. Roles are
Owner, Dispatcher, and Viewer. Only an Owner can add or remove Tags and change
billing. A Dispatcher can draw geofences and acknowledge alerts. A Viewer is
read-only.
