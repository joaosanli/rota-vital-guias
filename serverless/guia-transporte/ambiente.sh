# Prepara o terminal (Git Bash) para rodar o SAM nesta pasta.
# Uso:  source ./ambiente.sh
#
# - ROTA_VITAL_RAIZ: caminho absoluto da API principal. O pom.xml da funcao compila o dominio
#   da Expedicao de la; precisa ser absoluto porque o sam build copia o codigo para uma pasta temporaria.
# - mvn: o SAM chama "mvn" para o runtime java21. Uso o Maven que o ./mvnw do projeto ja baixa.
# - sam: no Git Bash o "sam" do Windows e um .cmd; uso o caminho curto (sem espacos) do instalador.

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RAIZ="$(cd "$AQUI/../.." && pwd)"
export ROTA_VITAL_RAIZ="$(cd "$RAIZ" && pwd -W)"

MAVEN_BIN="$(ls -d "$HOME"/.m2/wrapper/dists/apache-maven-*/*/bin 2>/dev/null | head -1)"
if [ -z "$MAVEN_BIN" ]; then
  (cd "$RAIZ" && ./mvnw -v > /dev/null)   # baixa o Maven do wrapper na primeira vez
  MAVEN_BIN="$(ls -d "$HOME"/.m2/wrapper/dists/apache-maven-*/*/bin | head -1)"
fi
export PATH="$MAVEN_BIN:/c/PROGRA~1/Amazon/AWSSAMCLI/bin:$PATH"
export SAM_CLI_TELEMETRY=0
sam() { sam.cmd "$@"; }

echo "ROTA_VITAL_RAIZ=$ROTA_VITAL_RAIZ"
mvn -v | head -1
sam --version
