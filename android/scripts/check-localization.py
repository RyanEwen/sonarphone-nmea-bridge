#!/usr/bin/env python3
"""Validate Android catalog parity, printf arguments and declared language coverage."""
from pathlib import Path
from collections import Counter
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
TOKEN = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")


def catalog(path):
    """Read only Android string entries and reject duplicate or empty keys."""
    entries = ET.parse(path).getroot().findall("string")
    result = {entry.attrib["name"]: entry.text or "" for entry in entries}
    assert len(result) == len(entries), f"Duplicate key: {path}"
    assert all(value.strip('"') for value in result.values()), f"Empty text: {path}"
    return result


def placeholders(text):
    """Count every numbered printf argument and literal percent, independent of word order."""
    return Counter(TOKEN.findall(text))


source = catalog(RES / "values/strings.xml")
folders = {"values", "values-en", "values-en-rCA", "values-es", "values-fr", "values-fr-rCA", "values-de", "values-b+zh+Hans", "values-pt", "values-ja"}
actual = {path.parent.name for path in RES.glob("values*/strings.xml")}
assert actual == folders, f"Unexpected/missing catalogs: {actual ^ folders}"
for folder in sorted(folders):
    translated = catalog(RES / folder / "strings.xml")
    assert translated.keys() == source.keys(), f"Key mismatch: {folder}"
    for key, value in translated.items():
        assert placeholders(value) == placeholders(source[key]), f"Placeholder mismatch: {folder}/{key}"
        assert "—" not in value, f"Use ordinary punctuation: {folder}/{key}"

locales = ET.parse(RES / "xml/locales_config.xml").getroot()
namespace = "{http://schemas.android.com/apk/res/android}"
expected_tags = {"en-US", "en-CA", "es-ES", "fr-FR", "fr-CA", "de-DE", "zh-CN", "pt-BR", "ja-JP"}
assert {entry.attrib[namespace + "name"] for entry in locales} == expected_tags
language_source = (ROOT / "app/src/main/java/ca/dynamicsolutions/sonarbridge/AppLanguage.kt").read_text()
assert set(re.findall(r'"([a-z]{2}-[A-Z]{2})" to ', language_source)) == expected_tags
references = set()
for path in (ROOT / "app/src/main/java").rglob("*.kt"):
    references.update(re.findall(r"(?<!android\.)R\.string\.([a-z_]+)", path.read_text()))
for path in (ROOT / "app/src/main").rglob("*.xml"):
    references.update(re.findall(r"@string/([a-z_]+)", path.read_text()))
assert references <= source.keys(), f"Missing source strings: {references - source.keys()}"
assert source.keys() <= references, f"Unused source strings: {source.keys() - references}"
assert catalog(RES / "values-en-rCA/strings.xml") == source
assert catalog(RES / "values-fr-rCA/strings.xml") == catalog(RES / "values-fr/strings.xml")
print(f"PASS: {len(source)} strings in {len(folders)} catalogs; nine locale identities; key and placeholder parity")
