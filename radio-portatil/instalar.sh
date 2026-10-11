#!/data/data/com.termux/files/usr/bin/bash
# Instalação do Rádio Portátil no Termux (Android)
set -e
pkg update -y
pkg install -y python ffmpeg termux-api
termux-setup-storage   # pede permissão para aceder às músicas
mkdir -p ~/radio
cp "$(dirname "$0")/radio.py" ~/radio/radio.py
cat > ~/radio/ligar.sh <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock          # continua a emitir com o ecrã desligado
python ~/radio/radio.py "$@"
termux-wake-unlock
EOF
chmod +x ~/radio/ligar.sh
echo
echo "Pronto! Para pôr a rádio no ar:  ~/radio/ligar.sh"
echo "Opções: ~/radio/ligar.sh ~/storage/music --aleatorio --nome \"A Minha Rádio\""
