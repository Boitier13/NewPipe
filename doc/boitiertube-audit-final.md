# BoitierTube — audit final du 20 novembre 2026

> **Mise à jour** : ce document décrit le bilan historique de l’audit. Les correctifs qui suivent
> (runtimes épinglés/vérifiés, journal de récupération, mode portable isolé, traduction des réglages)
> et leur validation sont consignés dans [le rapport avant Windows 10](boitiertube-correctifs-avant-windows10.md).
> Utiliser ce nouveau rapport et ses artefacts pour les essais, pas les anciens installateurs ci-dessous.


## Conclusion et limites de preuve

Audit des sources, de l’historique Git, des références GitHub, des contrats de ressources,
des scripts et du pipeline Windows. Les corrections sont réalisées uniquement sur
`arena/1461b4cf-newpipe`, sans création/suppression de branche, sans modification de `dev`,
sans intégration amont et sans réécriture de l’historique.

Ce rapport ne certifie ni l’absence de toute vulnérabilité, ni le fonctionnement de tous les
services vidéo en conditions réelles. La session est Linux, sans Java, PowerShell ni bureau Windows.
Les preuves Windows proviennent des étapes réellement exécutées par GitHub Actions, pas d’une
simulation locale. Une recette interactive et une analyse des dépendances restent nécessaires.

## 1. Dépôt, publication et historique

État initial vérifié avec `git ls-remote --heads origin` :

| Branche réellement présente | Pointe initiale | Statut |
| --- | --- | --- |
| `arena/1461b4cf-newpipe` | `da4e029303edc7f4abc0b3eeed8ee73a46059b57` | Centrale, 55 commits atteignables absents de dev avant cet audit |
| `dev` | `91fb95939d2fb33689bb47a0b885ab36e228e86f` | Branche par défaut, conservée sans modification |
| `arena/4a0ce2e9-newpipe` | `ee3097b75479caf87d7dd064cdfa37a65c72060a` | Encore présente malgré le nettoyage signalé ; intégrée, laissée intacte |

Aucune branche `main`, aucune PR ouverte, aucune release GitHub listée au début de l’audit.
Le commit central initial reste ancêtre de la nouvelle centrale. Les cinq branches historiques
supprimées ou conservées n’ont pas besoin d’exister comme refs pour que leurs commits restent
atteignables : les sept pointes de l’inventaire initial sont toujours ancêtres de HEAD.

Le script `scripts/check_branch_consolidation.py` a réellement été réexécuté :
- preuve d’ascendance des sept pointes ;
- conservation des 373 clés du catalogue français initial avec les seuls arbitrages enregistrés ;
- sauvegardes, tests de sauvegarde, palette, typographie et fenêtre/tray préservés octet pour octet ;
- découverte des runtimes, contrôleur VLC, réglages desktop et tests Windows historiques préservés.

Le script de préparation des runtimes n’est volontairement plus comparé octet pour octet : il est
durci par cet audit. Les vérifications d’intégration ne constituent pas une équivalence formelle de
tous les comportements, mais complètent la preuve d’historique par des contrats de contenu.

## 2. Interface française et identité

### Réussites

- **384 clés de chaînes par défaut et 384 françaises**, aucune clé par défaut sans valeur française.
- XML valides, pas de doublons par locale, références Compose avec valeurs par défaut.
- Paramètres de formatage contrôlés ; une exception documentée : le message français de sélecteur
  de fichiers supprime volontairement un ancien paramètre Android `%1$s`.
- Nom de paquet/menu/fenêtre et icône BoitierTube cohérents ; PNG/ICO et tailles contrôlés.
- Palette bleue et contraste des couples de rôles testés ; cela ne certifie pas toute l’accessibilité UI.
- Source Sans 3, fichiers de police et licences OFL conservés ; crédits/licence amont conservés.
- Les identifiants de compatibilité et chemins historiques NewPipe ne sont pas remplacés aveuglément.

### Limites détectées

**Moyenne — francisation incomplète hors catalogues.** `JVMPlayerSettingsSections.kt` contient encore
12 affectations directes `title`/`summary` en anglais, plus des états comme `VLC detected`,
`Auto-detect (bundled)` et `FFmpeg not found`. Les 384 traductions ne prouvent donc pas une
interface intégralement française. Il faut extraire ces textes dans des ressources et faire une
recette de l’écran. Pas de remplacement global risquant de modifier les clés techniques.
Le menu tray est français en dur : il ne suit pas une locale non française.

## 3. Fonctionnalités et données

Travaux présents et relus : import d’abonnements CSV/JSON, audio multiformat, muxage FFmpeg avec
secours Java, progression/file/vitesse et logs, historique/recherche, playlists et abonnements,
lecture VLC et audio en arrière-plan/tray, économie de données et réglages de chemins.
Les noms de fichiers passent par `DesktopStreamHelper.sanitizeFilename`. FFmpeg est appelé avec
une liste d’arguments `ProcessBuilder`, sans construction d’une commande shell à exécuter.
Les logs ont une rotation limitée à environ 2 Mio, mais peuvent contenir chemins et arguments
sensibles : les relire/expurger avant partage.

**Non vérifiés en interaction** : recherche/lecture YouTube actuelle, vidéos longues, reconnexion,
annulation en plein muxage, sorties MP3/M4A/Opus, import CSV réel, fonctionnement de la tray et
thème Système sous Windows 10. Les tests unitaires ne remplacent pas ces scénarios.
Le secours Java comporte des limites de formats explicites (notamment certaines variantes WebM)
: ne pas promettre une conversion universelle si FFmpeg est absent.

## 4. Sauvegardes et restaurations

Revue des sources :
- ZIP strict à deux entrées `newpipe.db` / `newpipe.json`, noms non utilisés comme chemins d’extraction ;
- bornes compressées/décompressées, contrôle CRC, UTF-8 strict, doublons JSON rejetés ;
- contrôle intégrité SQLite, clés étrangères, version/schema et colonnes avant modification du vivant ;
- snapshot WAL via `VACUUM INTO`, verrouillage et transaction non annulable pendant la substitution ;
- copies de secours DB/préférences typées, rollback et conservation des copies si récupération incomplète ;
- export refusant un fichier existant/concurrent ; messages français ; nettoyage non bloquant.

La classe `DesktopBackupStoreTest` contient 19 tests et est incluse dans la tâche JVM sans filtre.
Voir les résultats Windows ci-dessous et les rapports publiés, pas une simple analyse de syntaxe.
Les sauvegardes sont compatibles **sous les contraintes de schéma et de format vérifiées** ; cela
ne signifie pas acceptation de toutes les archives Android historiques, formats anciens ou ZIP
avec entrées supplémentaires.

### Risques restant à traiter

**Élevée — isolation des préférences Java.** `SettingsModule.kt` utilise encore `Preferences.userRoot()`
sans sous-nœud propre à l’application. Importer/réinitialiser appelle `settings.clear()` sur les clés
racines de ce backend : cela peut affecter les clés qu’une autre application Java aurait stockées
au même nœud. Ce n’est pas une suppression de tous les sous-nœuds Java. Changer directement le nœud
ferait disparaître les réglages existants : prévoir une migration testée et des règles d’attribution,
pas un changement silencieux lors de cet audit.

**Élevée — interruption brutale pendant restauration.** Le rollback gère les exceptions mais la
substitution DB + préférences n’est pas une transaction atomique résistante à une coupure de courant.
Les copies sont conservées après interruption, sans reprise automatique de transaction au prochain
lancement. Une récupération manuelle peut être nécessaire ; ne pas supprimer les dossiers
`.boitiertube-backup-*`. Voir `boitiertube-backup-safety.md`.

**Moyenne — confiance/confidentialité de la sauvegarde.** Les ZIP ne sont ni chiffrés ni signés et
peuvent inclure des paramètres sensibles et des chemins d’exécutables. Importer seulement une
archive de confiance. La validité de structure ne prouve pas l’origine ou l’innocuité du contenu.

## 5. Défauts de sécurité corrigés

### Élevée — portée des cookies applicatifs

Avant correction, `DesktopDownloader.getCookies()` testait `url.contains("youtube.com")` et
ajoutait les cookies reCAPTCHA globaux sans vérifier la destination. Un hôte comme
`youtube.com.attacker.example`, une URL comportant YouTube dans son chemin, ou un service étranger
pouvait recevoir des cookies applicatifs s’ils étaient configurés. Un en-tête Cookie ajouté
manuellement n’offre pas non plus le cloisonnement d’une CookieJar lors des redirections.

Correction : parseur d’URL OkHttp, hôte égal à YouTube ou vrai sous-domaine, HTTPS obligatoire,
et CookieJar évaluée par OkHttp par requête. Les cookies mémorisés restent en mémoire comme avant ;
la sauvegarde automatique de nouveaux cookies de réponse n’est pas activée. Les cookies fournis
explicitement par les requêtes de l’extracteur ne sont pas réinterprétés par cette correction.

Quatre tests ajoutés dans `DesktopDownloaderCookieTest.kt` : domaines autorisés, domaines trompeurs,
userinfo/chemins/HTTP refusés, réévaluation de la CookieJar entre destinations et suppression/toggle.
Le test de redirection interroge la CookieJar pour chaque destination ; ce n’est pas un test réseau
end-to-end avec serveur HTTPS malveillant.

### Élevée — sortie arbitraire du script Windows

Le paramètre `OutputDir` de `prepare-windows-bundle.ps1` acceptait un dossier arbitraire ensuite
supprimé récursivement. Le répertoire temporaire fixe était également réutilisé/nettoyé.
Correction : sortie limitée à `desktopApp/app-resources/windows-x64`, résolution depuis la racine
du dépôt et non le dossier courant, refus des reparse points sur les ancêtres, workspace temporaire
unique par GUID. Les contrôles se font avant téléchargement ou nettoyage.

Le test PowerShell exécute la validation seule, sans création ni suppression de fichiers : sortie
attendue relative/absolue acceptée, racine et autres chemins arbitraires refusés. La branche de refus
des liens est contrôlée statiquement, sans création d’une jonction malveillante pendant cet audit.
Il ne s’agit pas d’un bac à sable contre un autre processus local hostile qui modifierait les chemins
entre validation et utilisation.

## 6. Runtimes et distribution Windows

### Installable

Gradle déclare MSI et EXE, icône, UUID de mise à niveau, installation par utilisateur et ressources
VLC/FFmpeg. JDK 21 + WiX restent nécessaires pour produire les installateurs.
La version de projet est **0.29.3**, non incrémentée par cet audit ; une release publique devra avoir
une politique de version/mise à niveau et une recette install/désinstall/upgrade.
Aucune étape de signature Authenticode n’est configurée ni validée dans ce pipeline.

### Sans installation / portable

`createDistributable` produit un dossier avec `BoitierTube.exe`, runtime Java et ressources natives.
Avant cet audit, ce dossier était construit/testé mais **seuls MSI/EXE étaient publiés**.
La CI archive maintenant le dossier entier en `BoitierTube-windows-x64.zip` et le publie sous
`boitiertube-windows-portable`. Ne pas copier uniquement l’EXE : il dépend de ses dossiers voisins.

**Moyenne — pas de mode données entièrement portable.** Le ZIP peut se lancer sans installation,
mais la DB reste sous `%APPDATA%/NewPipeDesktop` et les réglages dans Java Preferences. Il partage
donc les données du profil avec l’installation, et n’est pas un mode « toutes les données sur clé USB ».
Pas de migration de chemins ou d’identité technique lors de cet audit pour éviter une perte apparente.

### Chaîne d’approvisionnement et redistribution

**Élevée avant diffusion publique** : le script télécharge des versions dynamiques (`latest`/`last`),
avec repli VLC 3.0.21, sans empreinte attendue/signature vérifiée avant extraction et exécution.
HTTPS et les contrôles de taille/initialisation ne remplacent pas une vérification cryptographique.
Il faut épingler des versions maintenues et leurs empreintes authentifiées, conserver leur provenance
et tester la mise à jour. Aucune CVE précise n’est affirmée ici sans analyse des versions effectivement
embarquées.

**Élevée avant redistribution** : le script ne copie que l’exécutable FFmpeg et le sous-ensemble VLC
nécessaire à l’exécution, sans collecte explicite de leurs notices/licences/offres de sources dans
le dossier des runtimes. Les licences de l’application et de Source Sans sont présentes mais ne
prouvent pas que toutes les obligations des binaires tiers distribués sont satisfaites. Préparer
ce dossier de conformité avant une diffusion publique.

**Moyenne — visibilité sécurité des dépendances** : l’API Dependabot a répondu HTTP 403 avec le
message explicite « Dependabot alerts are disabled for this repository ». Aucun réglage du dépôt
n’a été changé. Pas de scan CVE exhaustif ou d’audit binaire effectué dans cette session.

## 7. Tests et compilation réellement exécutés

### Locaux

- `python scripts/check_desktop_identity.py` : **13 tests réussis**.
- `python scripts/check_final_audit.py` : **5 tests réussis** (contrats statiques, pas scanner sécurité).
- `python scripts/check_branch_consolidation.py` : **réussi**, sept pointes et contenus contrôlés.
- Analyse syntaxique tree-sitter des deux fichiers Kotlin réseau modifié/ajouté : **réussie**, pas compilation.
- `git diff --check` : **réussi**.
- `bash gradlew :shared:jvmTest :desktopApp:build :desktopApp:createDistributable` : **échec avant Gradle**,
  Java et JAVA_HOME absents. PowerShell local absent ; tests des scripts délégués à la CI Windows.

### CI Windows

- Ancien run **44743615184**, code `9a2bbb691c` : succès complet attesté avant cet audit ; artefact
  `boitiertube-windows` ID `14632388560`, 415 083 969 octets. Ne pas utiliser cet ancien installateur
  pour prétendre disposer des corrections de sécurité nouvelles.
- Run **44749501959**, code `037e8fb64f` : échec du test de chemins avant compilation. Les cas négatifs
  laissaient un code de sortie enfant non nul repris par le wrapper Actions. Correction `10670a4e72` :
  sortie explicite 0 seulement après toutes les assertions, et annotation en cas d’exception.
- Run **44749673384**, code `10670a4e72` : intermédiaire sans correction des cookies.
- Run final **[44749905084](https://github.com/Boitier13/NewPipe/actions/runs/44749905084)**,
  code **`0faf8cde9209cd6acc38eee412a597b0c4121e92`** : **SUCCÈS**, job en **6 min 43 s**.
  Contrôles statiques et chemins PowerShell, tests JVM sans filtre, contrôles desktop,
  build/distribution, smoke test réel FFmpeg/VLC, MSI/EXE, archive ZIP et uploads : toutes ces
  étapes sont confirmées `success` par l’API GitHub. Les classes de 19 tests de sauvegarde et de
  4 tests de cookies font partie de la suite JVM ; les nombres individuels ne sont pas présentés
  comme un comptage extrait des XML dans cette session.
  Avertissement non bloquant : sauvegarde du cache Gradle via `tar.exe` échouée, sans échec du job.

### Artefacts finaux confirmés par GitHub

| Artefact | Contenu | Taille de l’archive Actions | Lien |
| --- | --- | ---: | --- |
| `boitiertube-windows` | Installateurs MSI et EXE | 415 091 826 octets | [Télécharger](https://github.com/Boitier13/NewPipe/actions/runs/44749905084/artifacts/14635920377) |
| `boitiertube-windows-portable` | `BoitierTube-windows-x64.zip`, dossier complet avec `BoitierTube.exe` | 211 280 175 octets | [Télécharger](https://github.com/Boitier13/NewPipe/actions/runs/44749905084/artifacts/14634304571) |
| `boitiertube-test-reports` | Rapports JVM et desktop | 17 993 octets | [Consulter/télécharger](https://github.com/Boitier13/NewPipe/actions/runs/44749905084/artifacts/14634669003) |

Ces trois artefacts sont confirmés non expirés au dernier contrôle. Leurs tailles sont celles des
archives Actions, pas les tailles individuelles des exécutables. Ils n’ont pas été téléchargés ou
exécutés dans la session Linux et ne sont pas ajoutés au dépôt. Ils ne constituent pas une release
GitHub permanente : conserver les fichiers selon la politique de rétention souhaitée.
La CI vérifie explicitement `BoitierTube.exe` avant archivage du ZIP et exécute les runtimes depuis
la distribution avant publication ; l’extraction/démarrage interactif du ZIP sur un PC reste à tester.

Empreintes fournies par l’API pour les **archives Actions** (pas pour chaque installateur) :
- installateurs : `sha256:f16401e97ba0b73687c62cf707f957fcb78b7700821f7e0cef6402ba5f3eed7c` ;
- portable : `sha256:f842c33f4a7847c464b8670aa2dd5b5e1c17ece8763a0d8fe8f69afa83cb5924` ;
- rapports : `sha256:1c7935122aa43557aa789d80a3e635674d55db152ab4f47140b0dba66812d667`.

Le commit de rapport qui suit ne change que la documentation. Le code et le workflow finaux restent
ceux du SHA validé. Le run intermédiaire 44749673384 peut terminer séparément ; ce n’est pas la
preuve des corrections réseau finales.

Les liens et métadonnées ci-dessus ont été relevés après succès de la validation. Les rapports
JVM/desktop sont maintenant publiés même si une étape ultérieure de packaging échoue.
Les logs bruts Actions ne sont pas téléchargeables depuis ce bac à sable (redirection réseau bloquée) ;
les conclusions des étapes, annotations et métadonnées d’artefacts sont accessibles via l’API GitHub.

## 8. Priorités restantes

1. **Avant diffusion publique** : isoler/migrer les préférences avec tests, documenter/tester la reprise
   après coupure, épingler/vérifier les runtimes et compléter leurs obligations de redistribution.
2. Recette Windows 10 x64 : installation, mise à niveau, ZIP extrait, VLC/FFmpeg sans installation
   système, lecture/long téléchargement, annulation, sauvegarde/restauration sur profil de test.
3. Terminer la francisation des littéraux desktop et vérifier visuellement tous les écrans.
4. Préparer version de release, signature, empreintes publiées et analyse de dépendances.
5. Si un vrai mode données portable est souhaité, le concevoir séparément avec isolation/migration.

Aucune branche supplémentaire n’a été supprimée. Aucun ancien commit central n’a été perdu.
Les défauts structurels de données ne sont pas modifiés à l’aveugle : la migration exige une tâche
et des tests dédiés. L’application n’est pas déclarée prête pour une diffusion publique sans réserve.
