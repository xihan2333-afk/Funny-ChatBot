#!/usr/bin/env bash
# ES 索引模板与 ILM 策略初始化（005-observability-safety，R19 / data-model 3.1 节）：
# traces-* 与 logs-agent-* 保留 14 天、logs-system-* 保留 7 天，数据流按天滚动。
# compose 首启由 es-init 服务执行一次；幂等（PUT 语义），手动重跑无副作用。
#
# 环境变量：ES_URL（默认 http://localhost:9200）、ES_USERNAME（默认 elastic）、
# ES_PASSWORD（必填——生产形态 ES 开启安全认证，凭证从 .env 注入）。
set -euo pipefail

ES_URL="${ES_URL:-http://localhost:9200}"
ES_USERNAME="${ES_USERNAME:-elastic}"
ES_PASSWORD="${ES_PASSWORD:?必须经环境变量提供 ES_PASSWORD}"

DIR="$(cd "$(dirname "$0")" && pwd)"

put() {
  local path="$1" body="$2"
  curl -fsS -u "${ES_USERNAME}:${ES_PASSWORD}" \
    -X PUT -H 'Content-Type: application/json' \
    --data-binary "@${body}" "${ES_URL}/${path}"
  echo "  <- ${path}"
}

echo "== ILM 策略（14 天 / 7 天两条） =="
put "_ilm/policy/chatbot-observe-14d" "${DIR}/policy-14d.json"
put "_ilm/policy/chatbot-observe-7d" "${DIR}/policy-7d.json"

echo "== 索引模板（traces / logs-agent / logs-system 数据流） =="
put "_index_template/traces-template" "${DIR}/template-traces.json"
put "_index_template/logs-agent-template" "${DIR}/template-logs-agent.json"
put "_index_template/logs-system-template" "${DIR}/template-logs-system.json"

echo "== 校验 =="
curl -fsS -u "${ES_USERNAME}:${ES_PASSWORD}" "${ES_URL}/_ilm/policy/chatbot-observe-14d" \
  | grep -o '"min_age":"14d"' || { echo "ILM 14d 策略校验失败" >&2; exit 1; }
curl -fsS -u "${ES_USERNAME}:${ES_PASSWORD}" "${ES_URL}/_ilm/policy/chatbot-observe-7d" \
  | grep -o '"min_age":"7d"' || { echo "ILM 7d 策略校验失败" >&2; exit 1; }
echo "== es-init 完成 =="
