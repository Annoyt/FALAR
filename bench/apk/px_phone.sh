# Цвета точек экрана на самом телефоне — для стендовых скриптов (кладётся в /data/local/tmp).
#
#   sh /data/local/tmp/falar_px.sh <секунд> <x y> [<x y> …]
#
# Снимок без сжатия (screencap в сырой вид — 0,3 с против 2,3 с у png на Redmi) и из него RGB
# каждой точки; печатает «мс_эпохи r g b [r g b …]», пока не выйдут секунды (0 — один снимок).
# У сырого снимка 16 байт заголовка: ширина, высота, формат, цветовое пространство.
S=$1; shift; F=/data/local/tmp/falar_px.raw
end=$(( $(date +%s) + S ))
while :; do
  t=$(date +%s%3N); screencap $F
  w=$(od -An -tu4 -N4 $F | tr -d ' ')
  out=$t; n=0; x=0
  for v in "$@"; do
    if [ $((n % 2)) = 0 ]; then x=$v
    else out="$out $(dd if=$F bs=4 skip=$(( 4 + v * w + x )) count=1 2>/dev/null | od -An -tu1 | cut -c1-12)"; fi
    n=$((n + 1))
  done
  echo $out
  [ $(date +%s) -ge $end ] && break
done
rm -f $F
