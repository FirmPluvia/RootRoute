#!/system/bin/sh
set -eu
PATH=/system/bin:/system/xbin
umask 077
cd "$1"
shift
PORT=45893
REDIR_PORT=45895
GLOBAL_PORT=45896
GLOBAL_REDIR_PORT=45897
has_global=0
GLOBAL_MARK=0x30000000/0x30000000
REDIR=RR45895_NAT
redir_ipv4=0
TABLE=45893
PRIO=8900
MARK=0x10000000/0x10000000
OUT=RR45893_OUT
IN=RR45893_IN
GUARD=RR45893_GUARD
BOOT="$(cat /proc/sys/kernel/random/boot_id)"
LOCK="session-$BOOT"

fw() {
    case "$1" in 4) bin=iptables;; 6) bin=ip6tables;; esac
    shift
    "$bin" -w 3 "$@"
}

ignore() { "$@" 2>/dev/null || :; }

cleanup_family() {
    fam="$1"
    net="$2"
    if [ -f "$LOCK/out$fam" ]; then
        ignore fw "$fam" -t mangle -D OUTPUT -j "$OUT"
        ignore fw "$fam" -t mangle -F "$OUT"
        ignore fw "$fam" -t mangle -X "$OUT"
    fi
    if [ -f "$LOCK/in$fam" ]; then
        ignore fw "$fam" -t mangle -D PREROUTING -i lo -m mark --mark "$MARK" -j "$IN"
        ignore fw "$fam" -t mangle -F "$IN"
        ignore fw "$fam" -t mangle -X "$IN"
    fi
    if [ -f "$LOCK/rule$fam" ]; then
        ignore ip "-$fam" rule del pref "$PRIO" fwmark "$MARK" lookup "$TABLE"
    fi
    if [ -f "$LOCK/route$fam" ]; then
        ignore ip "-$fam" route del local "$net" dev lo table "$TABLE"
    fi
    if [ -f "$LOCK/filter$fam" ]; then
        ignore fw "$fam" -D INPUT -j "$GUARD"
        ignore fw "$fam" -F "$GUARD"
        ignore fw "$fam" -X "$GUARD"
    fi
}

bypass_subnets() {
    if [ "$1" = 4 ]; then
        echo "0.0.0.0/8 10.0.0.0/8 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 240.0.0.0/4"
    else
        echo "::/128 ::1/128 fc00::/7 fe80::/10 ff00::/8"
    fi
}

install_redirect() {
    fw 4 -t nat -N "$REDIR"
    touch "$LOCK/redirect"
    fw 4 -t nat -A "$REDIR" -o lo -j RETURN
    for subnet in $(bypass_subnets 4); do
        fw 4 -t nat -A "$REDIR" -d "$subnet" -j RETURN
    done
    for entry in "$@"; do
        target="$REDIR_PORT"
        case "$entry" in *:g) target="$GLOBAL_REDIR_PORT";; esac
        fw 4 -t nat -A "$REDIR" -p tcp -m owner --uid-owner "${entry%:g}" -j REDIRECT --to-ports "$target"
    done
}

install_family() {
    fam="$1"
    net="$2"
    shift 2
    routes="$(ip "-$fam" route show table "$TABLE" 2>&1)" || case "$routes" in
        *"FIB table does not exist"*) routes="";;
        *) echo "$routes"; return 1;;
    esac
    [ -z "$routes" ] || { echo "路由表 $TABLE 已占用"; return 1; }
    if ip "-$fam" rule show | grep -q "^$PRIO:"; then
        echo "路由优先级 $PRIO 已占用"; return 1
    fi
    fw "$fam" -t mangle -N "$OUT"
    touch "$LOCK/out$fam"
    fw "$fam" -t mangle -N "$IN"
    touch "$LOCK/in$fam"
    # OUTPUT sees the original route before MARK causes a new lookup.
    # Local delivery already uses lo, including addresses added after startup.
    fw "$fam" -t mangle -A "$OUT" -o lo -j RETURN
    for subnet in $(bypass_subnets "$fam"); do fw "$fam" -t mangle -A "$OUT" -d "$subnet" -j RETURN; done
    for proto in tcp udp; do
        if [ "$has_global" = 1 ]; then
            fw "$fam" -t mangle -A "$IN" -p "$proto" -m mark --mark "$GLOBAL_MARK" -j TPROXY --on-port "$GLOBAL_PORT" --tproxy-mark "$MARK"
        fi
        fw "$fam" -t mangle -A "$IN" -p "$proto" -j TPROXY --on-port "$PORT" --tproxy-mark "$MARK"
        if [ "$redir_ipv4" = 1 ] && [ "$fam:$proto" = 4:tcp ]; then continue; fi
        for entry in "$@"; do
            route_mark="$MARK"
            if [ "$has_global" = 1 ]; then
                route_mark=0x10000000/0x30000000
                case "$entry" in *:g) route_mark="$GLOBAL_MARK";; esac
            fi
            fw "$fam" -t mangle -A "$OUT" -p "$proto" -m owner --uid-owner "${entry%:g}" -j MARK --set-xmark "$route_mark"
        done
    done
    ip "-$fam" route add local "$net" dev lo table "$TABLE"
    touch "$LOCK/route$fam"
    ip "-$fam" rule add pref "$PRIO" fwmark "$MARK" lookup "$TABLE"
    touch "$LOCK/rule$fam"
    fw "$fam" -t mangle -I PREROUTING 1 -i lo -m mark --mark "$MARK" -j "$IN"
}

protect_port() {
    fam="$1"
    fw "$fam" -N "$GUARD"
    touch "$LOCK/filter$fam"
    for proto in tcp udp; do
        fw "$fam" -A "$GUARD" '!' -i lo -p "$proto" --dport "$PORT" -j DROP
        fw "$fam" -A "$GUARD" -i lo -p "$proto" --dport "$PORT" -m mark '!' --mark "$MARK" -j DROP
        if [ "$has_global" = 1 ]; then
            fw "$fam" -A "$GUARD" '!' -i lo -p "$proto" --dport "$GLOBAL_PORT" -j DROP
            fw "$fam" -A "$GUARD" -i lo -p "$proto" --dport "$GLOBAL_PORT" -m mark '!' --mark "$GLOBAL_MARK" -j DROP
        fi
    done
    if [ "$redir_ipv4" = 1 ] && [ "$fam" = 4 ]; then
        fw 4 -A "$GUARD" -p tcp --dport "$REDIR_PORT" -m conntrack --ctstate DNAT -j RETURN
        fw 4 -A "$GUARD" -p tcp --dport "$REDIR_PORT" -j DROP
        if [ "$has_global" = 1 ]; then
            fw 4 -A "$GUARD" -p tcp --dport "$GLOBAL_REDIR_PORT" -m conntrack --ctstate DNAT -j RETURN
            fw 4 -A "$GUARD" -p tcp --dport "$GLOBAL_REDIR_PORT" -j DROP
        fi
    fi
    fw "$fam" -I INPUT 1 -j "$GUARD"
}

session_alive() {
    [ -s "$LOCK/owner" ] || return 1
    owner="$(cat "$LOCK/owner")"
    case "$owner" in ''|*[!0-9]*) return 1;; esac
    [ -r "/proc/$owner/cmdline" ] || return 1
    tr '\000' '\n' < "/proc/$owner/cmdline" 2>/dev/null | grep -Fxq './route.sh'
}

recover() {
    if [ -f "$LOCK/redirect" ]; then
        ignore fw 4 -t nat -D OUTPUT -p tcp -j "$REDIR"
        ignore fw 4 -t nat -F "$REDIR"
        ignore fw 4 -t nat -X "$REDIR"
    fi
    cleanup_family 4 0.0.0.0/0
    cleanup_family 6 ::/0
    if [ -s "$LOCK/core" ]; then
        oldpid="$(cat "$LOCK/core")"
        if [ "$(readlink "/proc/$oldpid/exe" 2>/dev/null)" = "$PWD/mihomo" ]; then
            ignore kill "$oldpid"
        fi
    fi
    rm -rf "$LOCK"
}

packet_count() {
    if listing="$(fw "$1" -t "$2" -n -v -x -L "$3" 2>&1)"; then
        printf '%s\n' "$listing" | awk -v target="$4" '$3 == target { sum += $1 } END { printf "%.0f", sum }'
    else
        printf '不可读（%s）' "$listing"
    fi
}

diagnose_family() {
    family="$1"
    address="$2"
    marked="$(packet_count "$family" mangle "$OUT" MARK)"
    intercepted="$(packet_count "$family" mangle "$IN" TPROXY)"
    printf 'IPv%s · 应用标记 %s 包 · TPROXY 命中 %s 包\n' "$family" "$marked" "$intercepted"
    if route="$(ip "-$family" route get "$address" mark "${MARK%/*}" 2>&1)"; then
        if printf '%s\n' "$route" | awk 'NR == 1 && $1 == "local" && / dev lo( |$)/ { found = 1 } END { exit !found }'; then
            echo "  带标记路由 → 本机回环"
        else
            echo "  带标记路由 → 未回环"
        fi
    else
        printf '  带标记路由无法查询：%s\n' "$route"
    fi
}

case "$1" in
status)
    if [ -f "$LOCK/ready" ] && session_alive; then
        echo "运行中"
        if [ -s "$LOCK/ready" ]; then
            printf 'RR_STARTED_ELAPSED=%s\n' "$(cat "$LOCK/ready")"
        fi
    elif [ -s "$LOCK/owner" ] && ! session_alive; then
        echo "守护进程已退出，请点击停止清理路由"
    elif [ -d "$LOCK" ]; then
        echo "启动或停止中"
    else
        echo "已停止"
    fi
    [ ! -f session.log ] || tail -n 20 session.log
    [ ! -s launch.log ] || tail -n 5 launch.log
    exit 0;;
diagnose)
    if [ ! -f "$LOCK/ready" ] || ! session_alive; then
        echo "代理当前未就绪，请先启动代理"
        exit 0
    fi
    echo "本次启动后的累计包数"
    if [ -f "$LOCK/redirect" ]; then
        printf 'IPv4 TCP 兼容模式 · REDIRECT 命中 %s 次（仅新连接）\n' "$(packet_count 4 nat "$REDIR" REDIRECT)"
    fi
    diagnose_family 4 198.51.100.1
    diagnose_family 6 2001:db8::1
    exit 0;;
stop)
    if [ -d "$LOCK" ]; then
        touch "$LOCK/stop"
        count=0
        while [ -d "$LOCK" ] && [ "$count" -lt 20 ]; do
            if [ -s "$LOCK/owner" ] && ! session_alive; then
                recover
                break
            fi
            sleep 1
            count=$((count + 1))
        done
        [ ! -d "$LOCK" ] || { echo "停止超时"; exit 1; }
    fi
    exit 0;;
run) shift;;
*) exit 2;;
esac

if [ "${1:-}" = --redir ]; then
    redir_ipv4=1
    shift
fi
[ "$#" -gt 0 ] || exit 2
for entry in "$@"; do
    case "$entry" in *:g) has_global=1;; esac
    uid="${entry%:g}"
    case "$uid" in ''|*[!0-9]*) exit 2;; esac
    [ "$uid" -ge 10000 ] || exit 2
done
[ -d "$LOCK" ] || exit 1
printf '%s\n' "$$" > "$LOCK/owner"
: > session.log
exec >> session.log 2>&1
pid=
stage="初始化"
finish() {
    result=$?
    if [ "$result" -ne 0 ]; then
        printf '失败阶段：%s（退出码 %s）\n' "$stage" "$result"
    fi
    rm -f "$LOCK/ready"
    recover
    if [ -n "$pid" ]; then wait "$pid" 2>/dev/null || :; fi
}
trap finish EXIT
trap 'exit 1' HUP INT TERM
stage="IPv4 代理端口保护"
protect_port 4
stage="IPv6 代理端口保护"
protect_port 6
stage="等待 Mihomo 监听"
./mihomo -d "$PWD" -f "$PWD/config.yaml" &
pid=$!
printf '%s\n' "$pid" > "$LOCK/core"
# Confirm that both proxy sockets belong to the core before capturing traffic.
socket_ready() {
    procfile="$1"
    port="$2"
    address="$3"
    inodes="$(awk -v port="$(printf '%04X' "$port")" -v address="$address" 'toupper($2) ~ ("^" address ":" port "$") {print $10}' "/proc/net/$procfile")" || return 1
    for inode in $inodes; do
        for fd in /proc/"$pid"/fd/*; do
            if [ "$(readlink "$fd" 2>/dev/null)" = "socket:[$inode]" ]; then return 0; fi
        done
    done
    return 1
}
count=0
until socket_ready tcp6 "$PORT" '0+' && socket_ready udp6 "$PORT" '0+' &&
      { [ "$redir_ipv4" = 0 ] || socket_ready tcp "$REDIR_PORT" 0100007F; } &&
      { [ "$has_global" = 0 ] || {
          socket_ready tcp6 "$GLOBAL_PORT" '0+' && socket_ready udp6 "$GLOBAL_PORT" '0+' &&
          { [ "$redir_ipv4" = 0 ] || socket_ready tcp "$GLOBAL_REDIR_PORT" 0100007F; }
      }; }; do
    [ ! -f "$LOCK/stop" ] || exit 0
    kill -0 "$pid" 2>/dev/null || { echo "Mihomo 启动失败"; exit 1; }
    [ "$count" -lt 60 ] || { echo "代理端口启动超时"; exit 1; }
    sleep 1
    count=$((count + 1))
done
stage="IPv4 TPROXY 与策略路由"
install_family 4 0.0.0.0/0 "$@"
stage="IPv6 TPROXY 与策略路由"
install_family 6 ::/0 "$@"
if [ "$redir_ipv4" = 1 ]; then
    stage="IPv4 TCP REDIRECT"
    install_redirect "$@"
    fw 4 -t nat -I OUTPUT 1 -p tcp -j "$REDIR"
    echo "IPv4 TCP 兼容模式已启用；UDP 和 IPv6 继续使用 TPROXY"
fi
stage="启用应用流量接管"
fw 4 -t mangle -I OUTPUT 1 -j "$OUT"
fw 6 -t mangle -I OUTPUT 1 -j "$OUT"
awk '{print int($1)}' /proc/uptime > "$LOCK/started"
mv "$LOCK/started" "$LOCK/ready"
while [ ! -f "$LOCK/stop" ] && kill -0 "$pid" 2>/dev/null; do
    sleep 1
    if [ "$(wc -c < session.log)" -gt 262144 ]; then
        : > session.log
    fi
done
