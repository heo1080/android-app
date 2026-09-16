# DolphinAssistant 자연음성 게이트웨이

차량 APK에 Google Cloud 서비스 계정 키를 넣지 않기 위한 작은 Cloud Run 게이트웨이입니다.

구조:

`차량 앱 -> HTTPS + X-Dolphin-Token -> Cloud Run -> Google Cloud Text-to-Speech Chirp 3 HD -> LINEAR16 WAV -> 차량 캐시 -> BYD legacy stream14`

실제 주행 중 경고 재생은 네트워크를 사용하지 않습니다. 게이트웨이는 새 문장을 처음 만들거나 사용자가 캐시를 미리 생성할 때만 사용합니다.

지원 음성:

- `ko-KR-Chirp3-HD-Aoede` (기본)
- `ko-KR-Chirp3-HD-Kore`
- `ko-KR-Chirp3-HD-Charon`
- `ko-KR-Chirp3-HD-Fenrir`

## 빠른 배포

Google Cloud 프로젝트에서 결제와 Cloud Text-to-Speech API를 활성화한 뒤 Cloud Shell에서 실행합니다.

```bash
PROJECT_ID="YOUR_PROJECT_ID"
REGION="asia-northeast1"
gcloud config set project "$PROJECT_ID"
gcloud services enable texttospeech.googleapis.com run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com

TOKEN="$(openssl rand -hex 32)"
printf '%s' "$TOKEN" | gcloud secrets create dolphin-gateway-token --data-file=-

gcloud iam service-accounts create dolphin-voice --display-name="Dolphin voice gateway"
VOICE_SA="dolphin-voice@${PROJECT_ID}.iam.gserviceaccount.com"
gcloud projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:${VOICE_SA}" --role="roles/serviceusage.serviceUsageConsumer"
gcloud secrets add-iam-policy-binding dolphin-gateway-token --member="serviceAccount:${VOICE_SA}" --role="roles/secretmanager.secretAccessor"

gcloud run deploy dolphin-voice-gateway \
  --source ./voice-gateway \
  --region "$REGION" \
  --service-account "$VOICE_SA" \
  --allow-unauthenticated \
  --set-secrets DOLPHIN_GATEWAY_TOKEN=dolphin-gateway-token:latest

echo "차량 앱에 입력할 토큰: $TOKEN"
```

`--allow-unauthenticated`는 Cloud Run IAM 인증을 차량에서 직접 구현하지 않기 위한 것입니다. 실제 `/synthesize` 요청은 반드시 긴 무작위 `X-Dolphin-Token`이 일치해야 하며, 토큰은 차량에서 Android Keystore AES/GCM으로 암호화 저장됩니다. `/health`만 공개 상태 확인용입니다.

배포가 끝나면 Cloud Run이 출력한 `https://...run.app` URL과 위 TOKEN을 차량 앱의 **자연음성 HD 캐시 · v30.6** 패널에 넣습니다.

## 테스트

```bash
SERVICE_URL="https://YOUR_SERVICE.run.app"
curl "$SERVICE_URL/health"

curl -sS -X POST "$SERVICE_URL/synthesize" \
  -H "X-Dolphin-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"text":"전방 차량이 출발했습니다.","voice":"ko-KR-Chirp3-HD-Aoede"}' \
  --output test.wav
file test.wav
```

## 안전 원칙

- Google 서비스 계정 JSON 키를 APK/차량에 저장하지 않습니다.
- 게이트웨이 토큰이나 전체 사용자 문장은 Dolphin 로그에 기록하지 않습니다.
- 차량에서는 합성 결과를 앱 전용 저장소에 캐시합니다.
- 캐시 miss/서버 오류 시 기존 로컬 TTS가 즉시 fallback 합니다.
- 캐시된 음성은 실차에서 검증된 stream14로 직접 재생합니다.
