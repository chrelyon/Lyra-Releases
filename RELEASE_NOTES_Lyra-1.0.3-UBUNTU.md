# Lyra 1.0.3 · Passaggio backup musicale a Ubuntu (build 40)

Derivata dai sorgenti puliti Lyra 1.0.2. Nessun intervento su InnerTube, Android Auto, algoritmo musicale, motore audio o classifier.

- Nuovo pulsante Impostazioni → Backup musicale → **Passa al server Ubuntu (dopo importazione)**.
- Riutilizza l'URL Funnel già salvato da Lyra 1.0.2 per il classifier, ma NON il token classifier per il profilo.
- Verifica `/v2/health` e richiede `/v2/restore` autenticato con il codice di recupero Lyra esistente; passa solo se la stessa identità è già importata su Ubuntu.
- Se nella build rimane configurato il vecchio URL Cloudflare `LYRA_PROFILE_SYNC_URL`, confronta `updatedAtMs` del profilo tra Cloudflare e Ubuntu: non passa a un backup più vecchio.
- L'indirizzo Ubuntu verificato viene fissato nelle preferenze locali; cambiare l'URL classifier non può deviare inavvertitamente il backup.
- Finché non si preme il pulsante e la verifica non riesce, la sincronizzazione resta su Cloudflare (o continua offline se non raggiungibile).
- L'identità locale e il codice di recupero `LYRA2` restano invariati. Non cancellare i dati dell'app durante l'aggiornamento.
- Dopo il passaggio, il nome 'CloudProfileSync' rimane internamente per compatibilità ma la schermata mostra 'Backup musicale Ubuntu'.

**Non compilato né installato sul dispositivo in questa sessione.**

Non condividere i sorgenti comprensivi di `keystore.properties`, `local.properties` con credenziali o segreti. Il pacchetto sorgenti puliti non contiene tali file.
