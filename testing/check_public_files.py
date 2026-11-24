"""Reject unexpected Git-visible files before publishing this foundation.

Run from any directory: python testing/check_public_files.py
This is a file-boundary check; a human must still review content and diffs.
"""
from pathlib import Path
import hashlib
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = set("""
app/src/main/java/io/github/kannoncoding/wealthward/ui/Appearance.kt
app/src/main/java/io/github/kannoncoding/wealthward/ui/AccountMonth.kt
app/src/main/java/io/github/kannoncoding/wealthward/ui/CompactActions.kt
app/src/main/java/io/github/kannoncoding/wealthward/ui/LanguageSettings.kt
app/src/main/java/io/github/kannoncoding/wealthward/ui/EnglishCatalog.kt
app/src/main/res/drawable-nodpi/cat_launch_art.png
app/src/main/res/drawable-nodpi/wealthward_logo.png
app/src/main/res/drawable/ic_notification_dollar.xml
app/src/main/res/values-v31/themes.xml
app/src/main/res/font/inter_variable.ttf
app/src/main/res/raw/inter_license.txt
app/src/test/java/io/github/kannoncoding/wealthward/ui/AccountMonthTest.kt
docs/monthly-rollover.md
app/src/main/java/io/github/kannoncoding/wealthward/data/F10Repository.kt
app/src/main/java/io/github/kannoncoding/wealthward/data/F10Summary.kt
app/src/main/java/io/github/kannoncoding/wealthward/ui/F10Screens.kt
app/src/androidTest/java/io/github/kannoncoding/wealthward/data/F10PersistenceTest.kt
app/src/androidTest/java/io/github/kannoncoding/wealthward/ui/F07UiTest.kt
app/src/main/java/io/github/kannoncoding/wealthward/ui/F07Screens.kt
app/src/main/java/io/github/kannoncoding/wealthward/ui/F07Forms.kt
app/src/main/java/io/github/kannoncoding/wealthward/domain/F07.kt
app/src/main/java/io/github/kannoncoding/wealthward/data/F07Schedule.kt
app/src/main/java/io/github/kannoncoding/wealthward/data/F07Restore.kt
app/src/main/java/io/github/kannoncoding/wealthward/data/F07Repository.kt
app/src/main/java/io/github/kannoncoding/wealthward/data/F07Codec.kt
app/src/androidTest/java/io/github/kannoncoding/wealthward/data/F07PersistenceTest.kt
app/src/main/java/io/github/kannoncoding/wealthward/data/F07Savings.kt
app/src/androidTest/java/io/github/kannoncoding/wealthward/ui/F08UiTest.kt
app/src/androidTest/java/io/github/kannoncoding/wealthward/data/F08PersistenceTest.kt
app/src/test/java/io/github/kannoncoding/wealthward/domain/F08ProjectionTest.kt
app/src/main/java/io/github/kannoncoding/wealthward/ui/F08Screens.kt
app/src/main/java/io/github/kannoncoding/wealthward/domain/F08.kt
app/src/main/java/io/github/kannoncoding/wealthward/data/F08Repository.kt
app/src/main/java/io/github/kannoncoding/wealthward/domain/F06.kt
app/src/main/java/io/github/kannoncoding/wealthward/data/F06Repository.kt
app/src/test/java/io/github/kannoncoding/wealthward/domain/F06PlanningTest.kt
app/src/androidTest/java/io/github/kannoncoding/wealthward/data/F06PersistenceTest.kt
.github/workflows/android.yml
.gitignore
README.md
app/build.gradle.kts
app/schemas/io.github.kannoncoding.wealthward.data.WealthDatabase/1.json
app/schemas/io.github.kannoncoding.wealthward.data.WealthDatabase/2.json
app/schemas/io.github.kannoncoding.wealthward.data.WealthDatabase/3.json
app/src/androidTest/java/io/github/kannoncoding/wealthward/data/MigrationTest.kt
app/src/androidTest/java/io/github/kannoncoding/wealthward/data/PersistenceTest.kt
app/src/androidTest/java/io/github/kannoncoding/wealthward/data/F05PersistenceTest.kt
app/src/androidTest/java/io/github/kannoncoding/wealthward/data/F05MigrationTest.kt
app/src/androidTest/java/io/github/kannoncoding/wealthward/ui/F05UiTest.kt
app/src/androidTest/java/io/github/kannoncoding/wealthward/ui/F06UiTest.kt
app/src/androidTest/java/io/github/kannoncoding/wealthward/ui/F06ReminderTest.kt
app/src/main/AndroidManifest.xml
app/src/main/java/io/github/kannoncoding/wealthward/MainActivity.kt
app/src/main/java/io/github/kannoncoding/wealthward/WealthWardApplication.kt
app/src/main/java/io/github/kannoncoding/wealthward/data/FinanceRepository.kt
app/src/main/java/io/github/kannoncoding/wealthward/data/F05Repository.kt
app/src/main/java/io/github/kannoncoding/wealthward/data/WealthDatabase.kt
app/src/main/java/io/github/kannoncoding/wealthward/domain/Finance.kt
app/src/main/java/io/github/kannoncoding/wealthward/domain/F05.kt
app/src/main/java/io/github/kannoncoding/wealthward/ui/MonthlySummary.kt
app/src/main/java/io/github/kannoncoding/wealthward/ui/WealthWardApp.kt
app/src/main/java/io/github/kannoncoding/wealthward/ui/F05Forms.kt
app/src/main/java/io/github/kannoncoding/wealthward/ui/F05Import.kt
app/src/main/java/io/github/kannoncoding/wealthward/ui/F06Screens.kt
app/src/main/java/io/github/kannoncoding/wealthward/notifications/LocalReminders.kt
app/src/main/res/values/themes.xml
app/src/main/res/drawable/ic_wealthward.xml
app/src/main/res/xml/data_extraction_rules.xml
app/src/test/java/io/github/kannoncoding/wealthward/domain/MoneyTest.kt
app/src/test/java/io/github/kannoncoding/wealthward/domain/F05MoneyTest.kt
app/src/test/java/io/github/kannoncoding/wealthward/ui/ImportTextTest.kt
build.gradle.kts
gradle.properties
gradle/libs.versions.toml
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
gradlew
gradlew.bat
settings.gradle.kts
testing/check_public_files.py
""".split())
BINARY_SHA256 = {
    "app/src/main/res/drawable-nodpi/cat_launch_art.png":
        "89e9cfcaad8cfb0b1e1fa4b6c12bc6892735e9d421dbb5464ebd1f44177b3a2f",
    "app/src/main/res/drawable-nodpi/wealthward_logo.png":
        "19f73e7a59543148c9e04b0090465f20e53150835570f8bc89d970fc56e97f90",
    "app/src/main/res/font/inter_variable.ttf":
        "4989b125924991b90d05b2d16e0e388c48f7d5bb8b30539bbf9c755278d0ccaf",
    "gradle/wrapper/gradle-wrapper.jar":
        "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7",
}


def main():
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT, check=True, capture_output=True,
    )
    paths = set(result.stdout.decode("utf-8").rstrip("\0").split("\0")) - {""}
    errors = [f"Unexpected public path: {name}" for name in sorted(paths - ALLOWED)]
    for name in sorted(paths & ALLOWED):
        path = ROOT / name
        if path.is_symlink() or not path.is_file():
            errors.append(f"Expected a regular file: {name}")
            continue
        data = path.read_bytes()
        if name in BINARY_SHA256:
            if hashlib.sha256(data).hexdigest() != BINARY_SHA256[name]:
                errors.append(f"Binary checksum mismatch: {name}")
        else:
            try:
                decoded = data.decode("utf-8")
                if "\0" in decoded:
                    errors.append(f"Binary content in text file: {name}")
            except UnicodeDecodeError:
                errors.append(f"Invalid UTF-8 text: {name}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"PASS: {len(paths)} public paths match the reviewed allowlist and binary checksums.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
