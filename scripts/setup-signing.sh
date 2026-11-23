#!/usr/bin/env bash
# Generates the stable release keystore and uploads it to GitHub Actions secrets.
# Requires: keytool (JDK 17), base64, gh (authenticated as the repo owner).
set -euo pipefail

REPO="${REPO:-stxtxm/flx4-pocket}"
ALIAS="${ALIAS:-flx4-pocket}"
KEYSTORE="keystore/release.jks"
CREDENTIALS="keystore/credentials.txt"

if [ -f "$KEYSTORE" ]; then
  echo "Refusing to overwrite $KEYSTORE. Back it up and reuse it for every release." >&2
  exit 1
fi

mkdir -p keystore
PASSWORD="$(openssl rand -hex 24)"

keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storepass "$PASSWORD" \
  -keypass "$PASSWORD" \
  -dname "CN=FLX4 Pocket, O=stxtxm, C=FR"

umask 077
cat > "$CREDENTIALS" <<EOF
keyAlias=$ALIAS
storePassword=$PASSWORD
keyPassword=$PASSWORD
EOF

echo
echo "Keystore:     $KEYSTORE"
echo "Credentials:  $CREDENTIALS (back both files up; never commit them)"
echo
echo "Configure GitHub Actions secrets with:"
echo "  gh secret set ANDROID_KEYSTORE_BASE64 -R $REPO < <(base64 -w0 $KEYSTORE)"
echo "  gh secret set ANDROID_STORE_PASSWORD -R $REPO --body \"\$(sed -n 's/^storePassword=//p' $CREDENTIALS)\""
echo "  gh secret set ANDROID_KEY_ALIAS -R $REPO --body $ALIAS"
echo "  gh secret set ANDROID_KEY_PASSWORD -R $REPO --body \"\$(sed -n 's/^keyPassword=//p' $CREDENTIALS)\""
echo
echo "Local signed build:"
echo "  export ANDROID_KEYSTORE_FILE=\"\$PWD/$KEYSTORE\""
echo "  export ANDROID_STORE_PASSWORD=\"\$(sed -n 's/^storePassword=//p' $CREDENTIALS)\""
echo "  export ANDROID_KEY_ALIAS=$ALIAS"
echo "  export ANDROID_KEY_PASSWORD=\"\$(sed -n 's/^keyPassword=//p' $CREDENTIALS)\""
echo "  ./gradlew assembleRelease"
