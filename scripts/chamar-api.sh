#!/usr/bin/env bash
# Faz 3 chamadas POST /guias e mostra o corpo, o status e o tempo de cada uma.
# Uso: ./scripts/chamar-api.sh   (com o sam local start-api rodando em outro terminal)
for i in 1 2 3; do
  echo "--- Chamada $i ($(date +%T)) ---"
  echo "\$ curl -X POST http://127.0.0.1:3000/guias -H \"Content-Type: application/json\" -d '{\"requisicaoId\":\"R-002\",\"hospital\":\"H-Caruaru\"}'"
  curl -s -w "\nHTTP %{http_code} em %{time_total}s\n" -X POST http://127.0.0.1:3000/guias \
    -H "Content-Type: application/json" \
    -d '{"requisicaoId":"R-002","hospital":"H-Caruaru"}'
done
