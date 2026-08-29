#!/usr/bin/env bash
# =====================================================================
# ExportFlow 演示数据生成脚本（MySQL / orders 表）
# =====================================================================
# 作用：当 orders 表为空时，批量生成确定性的演示订单数据；
#       若表中已有数据则直接跳过，绝不覆盖或清空任何已有数据。
#
# 用法（在 Git Bash / Linux / macOS 下执行）：
#   cd backend/scripts
#   ./seed-demo-data.sh                  # 默认生成 200,000 行订单
#   SEED_ROWS=50000 ./seed-demo-data.sh  # 自定义行数（1 ~ 1,000,000）
#
# 数据库连接参数默认与 backend/src/main/resources/application.yml 保持一致，
# 也可通过环境变量覆盖：DB_HOST / DB_PORT / DB_USER / DB_PASSWORD / DB_NAME。
#
# 前置条件：
#   1. 本机 MySQL 已启动（默认 127.0.0.1:3306，存在 exportflow 库与
#      exportflow/exportflow 账号）；
#   2. 后端至少成功启动过一次（由 Flyway 完成建表迁移，orders 表已存在）。
# =====================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_SQL_FILE="$SCRIPT_DIR/sql/seed-demo-data.sql"

# ---------- 一、可配置参数（均可用环境变量覆盖） ----------
SEED_ROWS="${SEED_ROWS:-200000}"        # 生成的订单行数
DB_HOST="${DB_HOST:-127.0.0.1}"         # 与 application.yml 的 datasource.url 对应
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-exportflow}"
DB_PASSWORD="${DB_PASSWORD:-exportflow}"
DB_NAME="${DB_NAME:-exportflow}"

# 行数合法性校验：SQL 侧递归 CTE 上限设为 100 万，超出直接拒绝
if ! [[ "$SEED_ROWS" =~ ^[0-9]+$ ]] || [[ "$SEED_ROWS" -lt 1 ]] || [[ "$SEED_ROWS" -gt 1000000 ]]; then
  echo "错误：SEED_ROWS 必须是 1 ~ 1000000 之间的整数（当前值：$SEED_ROWS）" >&2
  exit 2
fi

# ---------- 二、定位 mysql 客户端 ----------
# 优先使用 PATH 中的 mysql；找不到时按常见安装路径探测（Windows MySQL 安装器默认位置）。
MYSQL_BIN="$(command -v mysql || true)"
if [[ -z "$MYSQL_BIN" ]]; then
  for candidate in \
    "/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" \
    "/c/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe" \
    "/c/Program Files/MySQL/MySQL Server 9.0/bin/mysql.exe" \
    "/c/Program Files/MySQL/bin/mysql.exe"; do
    if [[ -x "$candidate" ]]; then
      MYSQL_BIN="$candidate"
      break
    fi
  done
fi
if [[ -z "$MYSQL_BIN" ]]; then
  echo "错误：未找到 mysql 客户端。请将其安装目录加入 PATH，" >&2
  echo "      或将 MySQL Server 安装到默认位置（脚本会自动探测）。" >&2
  exit 2
fi
echo "==> 使用 mysql 客户端: $MYSQL_BIN"

# ---------- 三、MySQL 执行封装 ----------
# 统一 batch 模式执行 SQL：--skip-column-names 使输出仅为纯数据，便于脚本判等比对。
# 密码经 MYSQL_PWD 环境变量传入，避免出现在命令行（消除明文密码告警）。
mysql_run() {
  MYSQL_PWD="$DB_PASSWORD" "$MYSQL_BIN" \
    --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" \
    --database="$DB_NAME" \
    --default-character-set=utf8mb4 \
    --batch --skip-column-names \
    "$@"
}

# ---------- 四、前置校验：库与表结构是否就绪 ----------
# orders 等 4 张业务表由 Flyway 在后端首次启动时创建；
# 若表不齐，说明迁移尚未执行，提示先启动一次后端。
table_count="$(mysql_run --execute "
  SELECT COUNT(*)
  FROM information_schema.tables
  WHERE table_schema = DATABASE()
    AND table_name IN ('orders', 'export_jobs', 'export_job_attempts', 'outbox_events')")"
if [[ "$table_count" != "4" ]]; then
  echo "错误：数据库 $DB_NAME 中缺少 ExportFlow 业务表（预期 4 张，实际 $table_count 张）。" >&2
  echo "      请先启动一次后端（./mvnw.cmd spring-boot:run）让 Flyway 完成建表迁移。" >&2
  exit 2
fi

# ---------- 五、空表判定：有数据则跳过，绝不覆盖 ----------
existing_rows="$(mysql_run --execute 'SELECT COUNT(*) FROM orders')"
if [[ "$existing_rows" != "0" ]]; then
  echo "==> orders 表已有 $existing_rows 行数据，跳过生成（如需重新生成，请先手工清空该表）。"
  exit 0
fi
echo "==> orders 表为空，开始生成 $SEED_ROWS 行演示数据..."

# ---------- 六、执行数据生成 ----------
# 先注入行数变量 @seed_rows，再送入生成 SQL；sed 用于剥离文件可能存在的 CRLF 行尾。
{
  echo "SET @seed_rows = $SEED_ROWS;"
  sed 's/\r$//' "$SEED_SQL_FILE"
} | mysql_run

# ---------- 七、结果校验与统计汇总 ----------
actual_rows="$(mysql_run --execute 'SELECT COUNT(*) FROM orders')"
if [[ "$actual_rows" != "$SEED_ROWS" ]]; then
  echo "错误：数据生成不完整（预期 $SEED_ROWS 行，实际 $actual_rows 行）。" >&2
  exit 1
fi

echo "==> 生成完成，共 $actual_rows 行订单。数据分布概览："
mysql_run --execute "
  SELECT CONCAT('    状态分布 | ', status, ': ', COUNT(*), ' 行') FROM orders GROUP BY status ORDER BY status;
  SELECT CONCAT('    渠道分布 | ', sales_channel, ': ', COUNT(*), ' 行') FROM orders GROUP BY sales_channel ORDER BY sales_channel;
  SELECT CONCAT('    时间范围 | ', MIN(created_at), ' ~ ', MAX(created_at)) FROM orders;
  SELECT CONCAT('    金额合计 | ', CAST(SUM(total_amount) AS CHAR), ' 元') FROM orders;"
echo "PASS: orders 表已装入 $actual_rows 行确定性演示数据"
