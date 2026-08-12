# Home Cast — app Android

App de mirroring d'écran (capture native `MediaProjection`) qui envoie le flux
en WebRTC vers le serveur `home-cast` (voir le projet serveur séparé).

## Comment obtenir l'APK (sans Android Studio)

Le code compile automatiquement via **GitHub Actions**, rien à installer
chez toi.

### 1. Crée un repo GitHub

Sur [github.com/new](https://github.com/new), crée un repo (ex: `home-cast-android`),
public ou privé, sans README initial.

### 2. Pousse ce dossier dedans

Depuis ton PC, dans le dossier `cast-android` :

```bash
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/<ton-user>/home-cast-android.git
git push -u origin main
```

### 3. Récupère l'APK compilé

- Va sur ton repo GitHub → onglet **Actions**
- Le workflow "Build APK" se lance automatiquement après le push (~3-5 min)
- Une fois vert ✅, clique dessus → en bas de la page, section **Artifacts**
  → télécharge `HomeCast-debug-apk` (c'est un zip contenant `app-debug.apk`)

### 4. Installe l'APK sur ton téléphone

- Transfère le `.apk` sur ton tel (câble, ou upload sur un Drive/cloud puis
  téléchargement)
- Ouvre le fichier : Android va probablement demander d'autoriser
  "installation depuis sources inconnues" la première fois — c'est normal
  pour un APK hors Play Store
- Installe

## Utilisation

1. Lance l'app "Home Cast"
2. Entre l'IP de ton serveur Debian (celle affichée dans les logs Docker,
   ex: `192.168.1.50`)
3. Appuie sur **MIROIR**
4. Autorise le partage d'écran dans le popup système Android
5. Le flux part vers le serveur, qui bascule automatiquement ta TV dessus

## Notes techniques

- `minSdk 26` (Android 8.0+) — nécessaire pour `MediaProjection` en foreground
  service proprement
- WebRTC via la lib `stream-webrtc-android` (build maintenu, pas de dépendance
  aux services Google)
- Aucun serveur STUN/TURN externe configuré — tout reste en LAN comme prévu
- Si tu changes de réseau Wi-Fi ou l'IP du serveur, il faut juste remettre à
  jour le champ IP dans l'app (sauvegardé automatiquement pour la prochaine fois)

## Prochaines améliorations possibles

- Découverte auto du serveur (au lieu de taper l'IP à la main), via une
  requête broadcast UDP ou en interrogeant `/api/tvs` sur un range d'IP
- Bouton "arrêter le mirroring" dans l'app (actuellement il faut fermer
  l'app ou couper le partage depuis la notif Android)
- Icône et écran de démarrage plus travaillés
