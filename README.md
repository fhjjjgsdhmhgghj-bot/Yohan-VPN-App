# Yohan VPN App

تطبيق أندرويد VPN حقيقي:

1. يطلب حساب SSH من **Yohan VPN API** (Railway)
2. يتصل عبر **Proxy + Payload** (YouTube / Snapchat)
3. يفتح جلسة **SSH** حقيقية
4. يشغّل **VpnService** النظامي + SOCKS5 محلي عبر قنوات SSH

## الاستخدام

1. انشر API من مستودع `bot` على Railway
2. ضع رابط الـ API في التطبيق
3. اختر السيرفر والبروفايل → اتصال

## البناء

Android Studio → Open → Sync → Run

أو GitHub Actions بعد إضافة workflow.
