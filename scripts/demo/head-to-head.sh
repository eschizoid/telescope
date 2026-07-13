#!/usr/bin/env bash
# Streams the telescope-vs-MapStruct head-to-head as a live reveal for the README GIF.
# Every line is REAL output from:  ./gradlew :examples:mapstruct-vs-telescope:test  (trimmed).
# Regenerate:  vhs scripts/demo/head-to-head.tape   (needs vhs, ttyd, ffmpeg)
set -uo pipefail
G='\033[32m'; D='\033[90m'; C='\033[36m'; Y='\033[33m'; B='\033[1m'; R='\033[0m'
L=0.18    # per-line reveal
A=0.52    # pause between acts
p(){ printf "%b\n" "$1"; sleep "${2:-$L}"; }

sleep 0.2; echo
p "  MapStruct and telescope map the same ${C}Order → OrderDto${R}, identically   ${G}✓${R}"
p "  Bidirectional for free — ${D}backward(forward(order)) == order${R}           ${G}✓${R}" "$A"
echo
p "  ${Y}Footgun${R}: MapStruct's default nulls an unmapped target, ${B}silently${R}"
p "    contactEmail = ada@example.com   |   ${Y}region = null${R}   ${D}← silent${R}   ${G}✓${R}" "$A"
echo
p "  Deep immutable update — original untouched"
p "    ${D}before${R}  price=10.00  price=5.00"
p "    ${D}after${R}   price=${B}20.00${R}  price=${B}10.00${R}                                    ${G}✓${R}" "$A"
echo
p "  ${B}And the mapper explains itself${R} — ${D}MapStruct is a black box:${R}"
p "    ${C}mapper.explain()${R}"
p "      ${G}✓${R} email → contactEmail"
p "      ${G}✓${R} name  → name"
p "    ${C}mapper.trace(order)${R}"
p "      ${G}✓${R} id        \"o-1\"                    → id \"o-1\""
p "      • customer  Customer[name=Ada,…]  → CustomerDto[…, contactEmail=…]   ${G}✓${R}" "$A"
echo
p "  ${G}${B}BUILD SUCCESSFUL${R}  ${D}— every claim is a passing test${R}"
sleep 4.2    # hold the finished frame so the viewer can read it before the loop
