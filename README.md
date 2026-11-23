# Rota Vital · Guias de transporte (Serverless)

Atividade individual 1 da Unidade II de Infraestrutura de Software (ADS · CESAR School · 2026.2).

Uma função serverless que recebe uma requisição do Rota Vital e gera a **guia de transporte** das bolsas de sangue. A função é acionada de duas formas: por **evento** (`sam local invoke`) e por **HTTP** (`sam local start-api` + `curl`). Com ela eu observei o cold start, o reaproveitamento do ambiente, o estado que não é garantido entre chamadas e o limite de tempo de execução.

**Caminho escolhido: A (AWS SAM CLI local)**, sem conta na AWS.

## Estrutura

```
rota-vital-guias/
├── template.yaml            # SAM: função + API POST /guias, Timeout em Globals
├── samconfig.toml           # config do SAM (warm_containers do start-api comentado, ver Passo 4)
├── hello_world/app.py       # o handler (lambda_handler)
├── events/pedido.json       # evento do API Gateway gerado com sam local generate-event
├── scripts/chamar-api.sh    # faz as 3 chamadas curl do Passo 4
├── scripts/gerar-prints.py  # transforma os logs de evidencias/*.txt em PNG
├── tests/unit/              # testes unitários do handler (unittest)
└── evidencias/              # saídas reais dos comandos (.txt) e prints (prints/*.png)
```

## Ambiente

| Ferramenta | Versão |
|---|---|
| Windows 11 + Git Bash | |
| Docker Desktop | 29.9.1 |
| AWS SAM CLI | 1.174.0 |
| Python do runtime (imagem `public.ecr.aws/lambda/python:3.12`) | 3.12.16 |

Na minha máquina o Python instalado é o 3.14, e não o 3.12. Por isso usei `sam build --use-container`: o build roda dentro da imagem oficial do Lambda com Python 3.12, sem precisar instalar o 3.12 no Windows. Evidência: [`evidencias/00-versoes.txt`](evidencias/00-versoes.txt).

## Como rodar

Pré-requisitos: Docker rodando e SAM CLI instalado. No Windows, usar o Git Bash.

```bash
# 1. build (dentro do container do Python 3.12)
sam build --use-container

# 2. invocação por evento (Passo 3)
sam local invoke -e events/pedido.json

# 3. HTTP (Passo 4): terminal 1
sam local start-api
#    terminal 2
./scripts/chamar-api.sh

# 4. reaproveitando o ambiente
sam local start-api --warm-containers LAZY
./scripts/chamar-api.sh

# 5. limite de tempo (Passo 5)
curl -X POST http://127.0.0.1:3000/guias -H "Content-Type: application/json" \
  -d '{"requisicaoId":"R-003","demorar":true}'

# testes unitários (opcional, não precisa do Docker)
python -m unittest discover -s tests/unit -t . -v
```

> Dica que me custou um tempo: o projeto precisa ficar num caminho curto. Na primeira tentativa a pasta estava num caminho de quase 200 caracteres e o `sam build` falhou com `WinError 206` (o nome do arquivo é muito grande), por causa do limite de 260 caracteres do Windows.

## O que fiz e o que observei

### Passo 2: projeto

`sam init` com *Hello World Example*, `python3.12` e pacote `zip`. No `template.yaml` troquei `Path: /hello` por `/guias` e `Method: get` por `post`, e coloquei o código da atividade no `hello_world/app.py`. Também esvaziei o `requirements.txt` (o template traz `requests`, que a função não usa).

**Problema que encontrei (base64).** Na primeira invocação com o evento gerado, o código original da atividade quebrou:

```
[ERROR] JSONDecodeError: Expecting value: line 1 column 1 (char 0)
  File "/var/task/app.py", line 8, in lambda_handler
    pedido = json.loads(event.get("body") or "{}")
```

O motivo: nesta versão do SAM CLI (1.174), o `sam local generate-event apigateway aws-proxy` grava o body **em base64**, com `"isBase64Encoded": true` (dá para conferir em `events/pedido.json`). O `json.loads` recebia `eyJyZXF1aXNpY2FvSWQi...` em vez do JSON. Criei a função `ler_body` no handler: ela decodifica o base64 quando `isBase64Encoded` é verdadeiro. O resto do código é igual ao do enunciado. O API Gateway de verdade também pode mandar o body em base64, então a correção faz sentido fora do ambiente local. O teste `test_gera_guia_com_body_base64` cobre esse caso.

### Passo 3: invocação com evento

Evidência: [`evidencias/02-invoke-evento.txt`](evidencias/02-invoke-evento.txt)

| Invocação | Tempo total (relógio) | `Duration` no REPORT | Resposta |
|---|---|---|---|
| 1ª | 8,49 s | 62,01 ms | 201, `invocacaoNesteAmbiente: 1` |
| 2ª | 8,13 s | 61,14 ms | 201, `invocacaoNesteAmbiente: 1` |

A primeira invocação de todas (a que deu o erro de base64, antes da correção) levou **39 s**, porque o Docker ainda estava preparando a imagem do runtime e o primeiro container. Depois disso as duas ficaram parecidas (~8 s). O `sam local invoke` **cria um container novo a cada execução**, então as duas foram cold start. Quase todo o tempo é para subir o container; o handler em si leva só ~60 ms. A diferença entre frio e quente aparece com clareza no Passo 4.

Observação: o `Init Duration` do emulador local aparece como ~0,05 ms, o que não é realista. O emulador (Runtime Interface Emulator) não mede o Init como o Lambda real. Por isso usei o tempo de relógio.

### Passo 4: HTTP com `start-api`

**Surpresa:** na primeira rodada sem flag, o contador foi 1, 2, 3, como se o ambiente estivesse sendo reaproveitado. Fui investigar e achei a causa: o `samconfig.toml` gerado pelo `sam init` já vem com

```toml
[default.local_start_api.parameters]
warm_containers = "EAGER"
```

Então o `sam local start-api` "sem flag" estava, na prática, rodando com EAGER (o log mostra `Initializing the lambda functions containers` logo na subida). Guardei essa rodada como [`evidencias/04-0-curl-samconfig-eager-padrao.txt`](evidencias/04-0-curl-samconfig-eager-padrao.txt), comentei essas linhas no `samconfig.toml` e refiz o experimento:

| Modo | Chamada 1 | Chamada 2 | Chamada 3 | `invocacaoNesteAmbiente` |
|---|---|---|---|---|
| Sem warm containers ([04a](evidencias/04a-curl-sem-warm.txt)) | 5,52 s | 2,10 s | 2,13 s | **1, 1, 1** |
| `--warm-containers LAZY` ([04b](evidencias/04b-curl-warm-lazy.txt)) | 3,76 s | 1,05 s | 1,08 s | **1, 2, 3** |
| EAGER (padrão do samconfig, [04-0](evidencias/04-0-curl-samconfig-eager-padrao.txt)) | 1,23 s | 1,04 s | 1,10 s | **1, 2, 3** |

* **Sem warm:** cada requisição ganha um container novo. A variável global `contador` sempre começa do zero e a resposta é sempre 1. Toda chamada paga o cold start.
* **LAZY:** o container só é criado na primeira requisição (por isso ela é mais lenta) e depois é reaproveitado. O `contador` continua vivo entre as chamadas: 1, 2, 3.
* **EAGER:** o container é criado já na subida do servidor, então nem a primeira chamada paga o cold start.

Também testei a validação: sem `requisicaoId` a função responde **400** com `{"erro": "requisicaoId obrigatório"}` (no fim do arquivo 04-0).

### Passo 5: limite de tempo

| Timeout no template | Resultado | Evidência |
|---|---|---|
| 3 s | **HTTP 502** `{"message":"Internal server error"}`. Log: `Function 'HelloWorldFunction' timed out after 3 seconds` | [05a](evidencias/05a-timeout-3s.txt), [log](evidencias/05a-servidor-timeout-3s.txt) |
| 10 s (após `sam build`) | **HTTP 201**, guia `G-R-003`. REPORT: `Duration: 5062.58 ms` | [05b](evidencias/05b-timeout-10s.txt), [log](evidencias/05b-servidor-timeout-10s.txt) |

Com 3 s a plataforma matou a execução no meio do `time.sleep(5)`, e o API Gateway local devolveu 502 para o cliente. A guia nunca foi gerada. Com 10 s a função terminou em ~5 s e respondeu normalmente. O `Billed Duration` foi de 5063 ms: na nuvem, esse tempo parado também seria cobrado.

## Respostas

**1. Por que a primeira invocação demorou mais que a segunda? O que acontece na fase de Init?**

Na primeira invocação não existe ambiente pronto, então a plataforma precisa criar um do zero. Esse é o *cold start*. Na fase de **Init**, o Lambda: (1) cria o ambiente de execução isolado (na AWS é uma microVM Firecracker; aqui é um container Docker); (2) baixa e monta o código da função; (3) inicia as extensões e o runtime (o interpretador Python 3.12); (4) roda o código que está **fora** do handler, ou seja, os `import` e as variáveis globais como `contador = 0`. Só depois disso vem a fase de **Invoke**, que chama o `lambda_handler`. Se o ambiente fica "quente", a próxima chamada pula o Init inteiro e vai direto para o Invoke.

No meu teste isso apareceu assim: a primeira execução de todas levou 39 s (preparar a imagem e o primeiro container), e no `start-api` com LAZY a 1ª chamada levou 3,76 s e as seguintes ~1 s. Com o `sam local invoke` as duas medições deram parecido (8,5 s e 8,1 s), porque esse comando sempre cria um container novo: as duas foram frias.

**2. O que o campo `invocacaoNesteAmbiente` mostrou com e sem `--warm-containers`? O que isso diz sobre guardar estado numa variável global?**

Sem warm containers foi sempre **1**, porque cada requisição caiu num ambiente novo e o `contador` foi recriado com 0. Com `--warm-containers LAZY` (e também com EAGER) foi **1, 2, 3**: o mesmo ambiente atendeu as três chamadas e a variável global sobreviveu entre elas.

Conclusão: variável global **não é lugar para guardar estado**. Ela só sobrevive enquanto a plataforma resolve manter aquele ambiente. A plataforma pode descartá-lo a qualquer momento, e em produção várias cópias rodam em paralelo, cada uma com o seu `contador`. Se o Rota Vital precisasse numerar guias assim, sairiam números repetidos e fora de ordem. Estado tem que ir para um serviço externo (banco, DynamoDB, Redis, S3). A global serve para **cache** do que é caro de criar e pode ser refeito, como uma conexão com o banco ou um cliente do SDK, aproveitando o reuso quando ele acontece.

**3. O que aconteceu com `demorar = true`? Qual é o tempo máximo de uma execução no Lambda, e o que fazer com um trabalho mais longo?**

Com Timeout de 3 s, a função foi interrompida em 3 s (`timed out after 3 seconds`), o cliente recebeu **HTTP 502** e a guia não foi gerada. Quando aumentei o Timeout para 10 s, a mesma chamada respondeu 201 em ~5 s.

O máximo no Lambda é **900 segundos (15 minutos)**. Para um trabalho maior que isso (ou que não precisa segurar o cliente esperando), dá para: quebrar o trabalho em partes menores e processá-las de forma assíncrona com uma fila (SQS), em que cada mensagem é uma execução curta; orquestrar as etapas com o **Step Functions**; ou rodar o processamento num container sem esse limite (**ECS/Fargate** ou **AWS Batch**). Também ajuda responder logo ao cliente (202 Accepted) e avisar quando terminar. E não adianta só aumentar o Timeout: o tempo parado também é cobrado.

**4. Quanto custa um milhão de execuções com 128 MB e 100 ms (us-east-1)?**

```
Requisições:  1.000.000 × (US$ 0,20 / 1.000.000)          = US$ 0,20

Computação:   128 MB = 128 / 1024 GB                       = 0,125 GB
              0,125 GB × 0,1 s                             = 0,0125 GB-s por execução
              0,0125 GB-s × 1.000.000 execuções            = 12.500 GB-s
              12.500 GB-s × US$ 0,0000166667               = US$ 0,2083

Total:        US$ 0,20 + US$ 0,2083                        ≈ US$ 0,41
```

Um milhão de guias custaria cerca de **41 centavos de dólar**. E isso ainda caberia na faixa sempre gratuita (1 milhão de requisições e 400 mil GB-s por mês), então na prática sairia de graça. Detalhe: no meu teste a função levou ~60 ms, e o Lambda cobra por milissegundo, então o custo real seria um pouco menor. Já com `demorar: true` (5 s) a parte de computação ficaria 50 vezes maior: 0,125 × 5 × 1.000.000 = 625.000 GB-s ≈ US$ 10,42.

**5. Em que parte do Rota Vital uma função assim faria sentido, e em qual não faria?**

*Faz sentido* em tarefas curtas, sem estado e disparadas por evento, com tráfego irregular: gerar a guia de transporte quando uma requisição é aprovada (como neste exemplo), gerar o PDF ou o QR code da guia, mandar notificações (e-mail, SMS, push) para o hospital e para o motorista, validar e registrar o recebimento quando a bolsa é entregue, ou rodar uma tarefa agendada, como alertar bolsas perto do vencimento. São coisas que acontecem algumas vezes por dia e às vezes em pico (uma emergência gera várias requisições de uma vez). Pagar por execução e escalar sozinho encaixa bem.

*Não faz sentido* no rastreamento em tempo real do transporte, que precisa de conexão aberta (WebSocket) recebendo posição a cada poucos segundos durante toda a viagem. Também não faz sentido no controle de estoque das bolsas, que precisa de consistência forte e transações, e onde o cold start numa urgência é um risco. Nem para cálculos longos de rotas e otimização que passem de 15 minutos. Para a API principal, que tem tráfego constante e mantém pool de conexões com o PostgreSQL, um serviço sempre ligado (como o container Java que já temos no projeto) tende a ser mais previsível e, com tráfego alto e constante, até mais barato.

## Testes

`python -m unittest discover -s tests/unit -t . -v`: 5 testes passando (body em texto, body em base64, falta de `requisicaoId` → 400, contador no mesmo ambiente e o próprio `events/pedido.json`). Evidência: [`evidencias/06-testes-unitarios.txt`](evidencias/06-testes-unitarios.txt).

## Evidências

Os arquivos `.txt` em `evidencias/` são a saída real dos comandos, capturada com `tee` no Git Bash. Os PNG em `evidencias/prints/` foram gerados a partir desses `.txt` com `scripts/gerar-prints.py`, só para facilitar a leitura.

| Arquivo | O que mostra |
|---|---|
| `00-versoes` | versões do SAM CLI, Docker e Python |
| `01-sam-build` | `sam build --use-container` com sucesso |
| `02-invoke-evento` | duas invocações com evento e tempos |
| `04-0-*` | `start-api` com o EAGER que vem no samconfig + chamada sem `requisicaoId` (400) |
| `04a-*` | `start-api` sem warm containers: 1, 1, 1 |
| `04b-*` | `start-api --warm-containers LAZY`: 1, 2, 3 |
| `05a-*` | timeout de 3 s: 502 e `timed out after 3 seconds` |
| `05b-*` | timeout de 10 s: 201 em ~5 s |
| `06-testes-unitarios` | testes unitários |

Vídeo (até 3 min): _link do vídeo aqui_

## Uso de IA

Declaração conforme a PO-CESAR-021.

* **Ferramenta:** Claude (Anthropic), via Claude Code.
* **Finalidade:** apoio para montar o projeto SAM, rodar os comandos dos experimentos, investigar dois problemas (o body em base64 do `generate-event` e o `warm_containers = "EAGER"` que já vem no `samconfig.toml`), escrever os testes unitários e o script dos prints, e revisar a redação do README e a conta de custo.
* **Como validei:** rodei todos os comandos e conferi as saídas guardadas em `evidencias/`. Confirmei a causa do erro de JSON abrindo `events/pedido.json` (`isBase64Encoded: true`). Confirmei a do contador lendo o `samconfig.toml` e repetindo o experimento com as linhas comentadas. Refiz a conta de custo à mão e conferi o limite de 15 minutos e os preços na documentação da AWS (cotas e preços do Lambda). Os testes unitários passam.
