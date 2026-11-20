#!/usr/bin/env bash
# 검증용 시드(shipment 1001, 1002 / 구매자 100 / 판매자 10)를 넣는다. 사용: VD_STATE=<state> scripts/seed-shipments.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/_lib.sh"
docker exec -i "$CONTAINER_NAME" mysql -uroot -p"$ROOT_PW" --default-character-set=utf8mb4 < "$HERE/seed-shipments.sql" 2>/dev/null
mysql_exec -e "SELECT id, order_id, buyer_id, seller_id, status FROM paldogotgan_delivery.shipment ORDER BY id"
