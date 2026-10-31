# Lyra 0.3.28 — Qualità dei metadati musicali

## Ambito

Questa è una patch incrementale per la base **Lyra 0.3.27 (versionCode 34)**.
Aggiorna l'app a **0.3.28 (versionCode 35)**, lasciando inalterati player,
Android Auto, firma APK, account e dati Cloudflare.

## Problema affrontato

YouTube Music, attraverso InnerTubeX, fornisce l'anno di un album/edizione,
ma non garantisce la data della **prima uscita della singola canzone**.
Un vecchio brano ristampato nel 2026 poteva quindi finire fra le *Novità*;
una compilation del 1990 poteva essere confusa con l'anno originale delle canzoni.

## Interventi

1. **Un verificatore comune** per Novità e Decenni (`ReleaseMetadataPolicy`).
2. **Confronto fra edizioni note dello stesso album** quando InnerTubeX restituisce
   `AlbumPage.otherVersions`: se esiste un'edizione precedente con lo stesso
   titolo normalizzato e artista identificabile, la nuova edizione non viene
   spacciata per una prima pubblicazione.
3. **Esclusione delle ristampe riconoscibili** da titolo del brano o dell'album:
   remaster, deluxe, reissue, compilation, raccolte di successi, remix,
   registrazioni alternative e varianti accelerate o rallentate.
4. **Date non disponibili o implausibili**: escluse dalle categorie basate
   sull'anno, senza impostare automaticamente l'anno corrente.
5. **Cache album condivisa** e a scadenza: 6 ore per i dati disponibili, 10
   minuti quando mancano, massimo 512 voci; massimo 4 richieste concomitanti.
6. **Nomi artisti deduplicati** e fallback ai metadati album per titolo
   e copertina quando non disponibili nel risultato del singolo brano.
7. **Descrizioni delle categorie corrette**: non si dichiara più verificata
   l'uscita originale del brano.

## Limiti importanti

- L'anno dell'edizione non è necessariamente quello della prima registrazione.
- `otherVersions` non è un catalogo discografico completo; se manca un'edizione
  precedente Lyra non può dimostrarne l'inesistenza.
- Un album omonimo di un artista diverso non viene trattato come precedente.
- La scelta più conservativa può portare a **meno risultati** per alcune
  categorie. Non vengono aggiunte canzoni con data non verificata per riempire
  automaticamente una playlist.
- Non sono introdotti servizi esterni di metadati, richieste di credenziali,
  migrazioni D1 o trasferimenti di dati personali.

## Collaudo raccomandato

- Novità: provare brani pubblicati quest'anno e vecchi successi rimasterizzati
  quest'anno; questi ultimi non dovrebbero più essere proposti se riconoscibili.
- Decenni: verificare un album originale del decennio e una compilation successiva.
- Verificare che Mix per me, playlist personali e Android Auto non siano cambiati.
- Aprire la stessa categoria una seconda volta e controllare i tempi di risposta.

## Storico

Per evitare il conflitto già riscontrato nei file locali, questa patch **non
modifica `LYRA_PROJECT_HISTORY.md`**. Puoi integrare questo documento nello
storico durante la preparazione della versione 1.0.
