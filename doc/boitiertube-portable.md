# Mode portable BoitierTube — activation explicite

Extraire **tout** le ZIP, puis ouvrir `Start-BoitierTube-Portable.cmd`.
Le lanceur définit `BOITIERTUBE_PORTABLE_ROOT` uniquement pour ce processus.
La base, les paramètres, les journaux, les fichiers temporaires de téléchargement et les
répertoires de téléchargement par défaut sont placés dans `data` à côté du lanceur.
Ne pas lancer depuis l’archive ZIP. Le dossier doit être accessible en écriture.

Ouvrir directement `BoitierTube.exe` conserve le comportement installé historique (profil
Windows / Java Preferences), sans copier ni migrer de données. Ne pas définir globalement
`BOITIERTUBE_PORTABLE_ROOT` : c’est le lanceur qui doit sélectionner explicitement le mode.
Les profils sont indépendants ; pour déplacer une bibliothèque, utiliser explicitement
export puis restauration dans le bon mode et vérifier le résultat avant de réutiliser l’autre.
La restauration remplace les données du profil cible et peut importer des chemins absolus :
vérifier les chemins de téléchargement, VLC et FFmpeg après tout transfert entre machines.

Le mode portable ne lit pas automatiquement les préférences installées. Les paramètres du profil
portable sont enregistrés dans `data/preferences.checked-json` avec contrôle SHA-256 et publication
atomique ; cela détecte des corruptions mais n’authentifie pas le propriétaire du fichier.
Si les paramètres sont illisibles, le dossier non inscriptible ou le système de fichiers ne permet
pas le remplacement atomique, l’application doit échouer plutôt que basculer dans le profil installé.
Une seule instance doit ouvrir le profil portable à la fois (verrou de fichier).

Toujours fermer l’application puis éjecter proprement la clé USB. Ne pas retirer le support pendant
une écriture ou une restauration. Un journal de restauration incomplet bloque l’ouverture et indique
les copies de secours à conserver ; ne pas les supprimer ni renommer sans procédure de récupération.
Le mode portable n’est pas un bac à sable : caches Java/OS, dialogues de fichiers, bibliothèques
natives et chemins personnalisés peuvent encore utiliser des emplacements extérieurs. Les téléchargements
avec un chemin explicitement personnalisé ne sont pas automatiquement déplacés.

La compatibilité effective des clés USB (FAT/exFAT/NTFS), le retrait du support, la tray et la lecture
interactive restent à tester sous Windows 10. Les MSI/EXE ne contiennent pas le lanceur portable :
ils gardent le comportement installé normal. Aucun changement de version/format du profil installé
n’est effectué et aucune migration du nœud racine Java n’est improvisée.
