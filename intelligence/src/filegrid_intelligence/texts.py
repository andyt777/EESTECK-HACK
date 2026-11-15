"""User-facing Romanian texts (centralised, ready for i18n).

Agent 02 rule (docs/ARCHITECTURE.md §A.3.11): describe observations, never assert causes.
"""

from __future__ import annotations


def num(value: float, digits: int = 2) -> str:
    return f"{value:.{digits}f}".replace(".", ",")


TYPE_LABELS = {
    "ZERO_CONSUMPTION": "Consum zero",
    "SPIKE": "Vârf de consum",
    "MISSING_READINGS": "Citiri lipsă",
    "REPEATED_VALUES": "Valori repetate",
    "LOW_VOLTAGE": "Tensiune scăzută la post",
    "NIGHT_EXPORT": "Export pe timp de noapte",
    "IMPOSSIBLE_VALUES": "Valori imposibile",
}

TITLES = {
    "ZERO_CONSUMPTION": "Consum zero la contorul {subject}",
    "SPIKE": "Vârf neobișnuit de consum la contorul {subject}",
    "MISSING_READINGS": "Citiri lipsă de la contorul {subject}",
    "REPEATED_VALUES": "Valori identice repetate la contorul {subject}",
    "LOW_VOLTAGE": "Tensiune sub nivelul rețelei la postul {subject}",
    "NIGHT_EXPORT": "Export de energie pe timp de noapte la contorul {subject}",
    "IMPOSSIBLE_VALUES": "Valori fizic imposibile la contorul {subject}",
}

HUMAN_CHECK = {
    "ZERO_CONSUMPTION": "Verificați dacă locul de consum este alimentat și dacă contorul comunică corect. Posibile "
                        "explicații (deconectare, defect de contor, locuință nelocuită) trebuie confirmate de un om.",
    "SPIKE": "Comparați cu istoricul clientului și verificați dacă există un consumator nou sau o eroare de măsurare. "
             "PPCastor nu poate stabili cauza din aceste date.",
    "MISSING_READINGS": "Verificați comunicația contorului (concentrator, GSM/PLC) și dacă citirile au ajuns cu "
                        "întârziere. Lipsa datelor nu indică singură o întrerupere.",
    "REPEATED_VALUES": "Verificați dacă contorul este blocat sau dacă transmite valori memorate. Necesită citire la "
                       "fața locului sau diagnoză de la distanță.",
    "LOW_VOLTAGE": "Un dispecer trebuie să verifice tensiunea la postul de transformare (măsurători SCADA sau echipă "
                   "în teren) înainte de orice concluzie despre cauză.",
    "NIGHT_EXPORT": "Verificați configurația prosumatorului (invertor, stocare, sens de măsurare). Exportul nocturn "
                    "poate avea explicații legitime care trebuie confirmate de un om.",
    "IMPOSSIBLE_VALUES": "Verificați contorul și lanțul de transmisie. Valorile nu pot proveni dintr-o măsurare "
                         "corectă și nu trebuie folosite la facturare până la verificare.",
}


def _where(meter: str, post: str, station: str) -> str:
    return f"Contorul {meter} (post {post}, stația {station})"


def impossible(meter, post, station, n, seq_from, seq_to, min_import, min_voltage) -> str:
    return (f"{_where(meter, post, station)} a raportat în {n} citiri (seq {seq_from}–{seq_to}) valori care nu pot "
            f"fi măsurate fizic: energie importată minimă {num(min_import, 3)} kWh și tensiune minimă "
            f"{num(min_voltage, 1)} V.")


def zero(meter, post, station, n, seq_from, seq_to, median) -> str:
    return (f"{_where(meter, post, station)} a raportat consum zero (import și export 0 kWh) în {n} intervale "
            f"consecutive (seq {seq_from}–{seq_to}), deși mediana sa în fereastra analizată este "
            f"{num(median, 3)} kWh/interval.")


def spike(meter, post, station, n, seq_from, seq_to, ratio, z) -> str:
    z_text = f", scor z robust {num(min(z, 99.0), 1)}" if z < 99 else ""
    return (f"{_where(meter, post, station)} a avut în {n} intervale (seq {seq_from}–{seq_to}) un consum de "
            f"{num(ratio, 1)} ori mai mare decât nivelul său obișnuit la aceeași oră a rețelei{z_text}.")


def missing(meter, post, station, n, seq_from, seq_to) -> str:
    return (f"{_where(meter, post, station)} nu a transmis {n} citiri consecutive (seq {seq_from}–{seq_to}); "
            "citirile ulterioare au sosit normal.")


def repeated(meter, post, station, n, seq_from, seq_to, samples) -> str:
    return (f"{_where(meter, post, station)} a transmis în {n} citiri (seq {seq_from}–{seq_to}) exact aceleași "
            f"valori la toate cele {samples} eșantioane pe minut (import, export, tensiune, curent).")


def night_export(meter, post, station, n, seq_from, seq_to, total, prosumer) -> str:
    kind = "prosumator" if prosumer else "neînregistrat ca prosumator"
    return (f"{_where(meter, post, station)}, {kind}, a exportat {num(total, 3)} kWh în {n} intervale de noapte "
            f"(seq {seq_from}–{seq_to}), când producția fotovoltaică estimată este zero.")


def low_voltage(post, station, n, seq_from, seq_to, post_v, grid_v, meters) -> str:
    return (f"La postul {post} (stația {station}) tensiunea medie raportată de {meters} contoare a fost "
            f"{num(post_v, 1)} V în {n} intervale (seq {seq_from}–{seq_to}), față de mediana rețelei de "
            f"{num(grid_v, 1)} V în aceleași intervale (diferență {num(grid_v - post_v, 1)} V).")


def incident_title(kind: str, post: str, station: str) -> str:
    return f"Posibilă anomalie de rețea: {TYPE_LABELS.get(kind, kind).lower()} la postul {post} ({station})"


def incident_description(observations: str, human_check: str, confidence: float) -> str:
    return (f"Constatare PPCastor, analiza rețelei (date simulate). Observație: {observations} Încredere: {num(confidence)}. "
            f"Verificare umană necesară: {human_check}")
