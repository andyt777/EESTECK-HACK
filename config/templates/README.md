# Template-uri de notificare

Textele mesajelor WhatsApp (docs/experienta-utilizator.md §10.3–§10.7 și §10.9), cu marcajul WhatsApp
pentru text îngroșat (`*text*`). `operational-alert.txt` este folosit pentru tipurile de incident care
nu au un text propriu în specificație.

Reguli ale renderer-ului (`MessageRenderer`):
- un rând care conține un câmp `{{...}}` fără valoare este omis complet — nu se inventează cauza,
  clienții afectați sau durata;
- valorile sunt curățate: parole, token-uri, chei și CNP-uri nu ajung în mesaj;
- fișierele se pot modifica fără recompilare (directorul `config/` este montat în api).
