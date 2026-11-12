# Security policy

Camora serves camera video on your local network and, if you turn it on, to the internet. Security
reports are taken seriously.

## Reporting a vulnerability

Please do **not** open a public issue. Email **Solvaris2@gmail.com** with a description, the steps to
reproduce, and the version you tested. You will get an answer within a week, and a fix or a plan as
soon as possible after that.

## Good to know

- Stream addresses contain a random secret key (`?k=`). Anyone with the full address can watch the
  shared cameras, so treat it like a password.
- Plain HTTP on the local network is not encrypted. Turn on HTTPS in the settings when that matters.
- Remote access without a VPN is off by default and shows a warning before it opens a router port.
  A VPN (for example Tailscale) is the recommended way to watch from outside.
