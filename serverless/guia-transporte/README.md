# Rota Vital · Guia de transporte (Serverless)

Atividade individual 1 da Unidade II de Infraestrutura de Software (ADS · CESAR School · 2026.2). Aluno: João Lucas Santos Lira.

Uma função HTTP serverless que recebe uma requisição do Rota Vital e devolve a **guia de transporte** do insumo. Ela é acionada por **evento** (`sam local invoke`) e por **HTTP** (`sam local start-api` + `curl`). Com ela observei o cold start, o reaproveitamento do ambiente, o estado que não é garantido entre chamadas e o limite de tempo de execução.

**Caminho escolhido: A (AWS SAM CLI local)**, sem conta na AWS, com **Java 21** (o enunciado usa Python, mas permite Java e Node).

## Por que em cima do projeto do grupo

Em vez de escrever uma função solta, fiz a função como **mais uma camada de apresentação do contexto de Expedição** do Rota Vital. A regra de negócio é a mesma que a API Spring usa, sem copiar código:

| O que a função usa | De onde vem |
|---|---|
| `PrepararTransferencia` (caso de uso) | `src/main/java/br/com/rotavital/expedicao/negocio/aplicacao/` |
| `PlanoTransferencia`, `JanelaColeta`, `PlanoRepository` | `src/main/java/br/com/rotavital/expedicao/negocio/dominio/` |
| `GuiaTransporteHandler` (o handler do Lambda) | `serverless/guia-transporte/GuiaTransporteFunction/src/main/java/br/com/rotavital/serverless/` |

Isso só é possível porque o domínio e a aplicação não dependem do Spring. O `ArquiteturaEmCamadasTest` do projeto já garante essa regra. O `pom.xml` da função adiciona o `src/main/java` da API como fonte, mas compila **só** `br/com/rotavital/expedicao/negocio/**` e o pacote `serverless`. Controller, Spring e banco ficam de fora.

A guia sai com a **janela de coleta de 2 horas, começando 1 hora depois** (regra do `PrepararTransferencia`). A quantidade inválida também é recusada pelo domínio (HTTP 422).

## Estrutura

```
serverless/guia-transporte/
├── template.yaml                 # SAM: função java21 + API POST /guias, Timeout em Globals
├── samconfig.toml                # warm_containers do start-api comentado (ver Passo 4)
├── ambiente.sh                   # prepara o Git Bash: Maven do ./mvnw, sam e ROTA_VITAL_RAIZ
├── events/pedido.json            # evento do API Gateway gerado com sam local generate-event
├── GuiaTransporteFunction/
│   ├── pom.xml                   # compila o handler + o domínio da Expedição da API
│   └── src/{main,test}/java/br/com/rotavital/serverless/
├── scripts/chamar-api.sh         # as 3 chamadas curl do Passo 4
├── scripts/gerar-prints.py       # transforma os logs de evidencias/*.txt em PNG
└── evidencias/                   # saídas reais dos comandos (.txt) e prints (prints/*.png)
```

## Ambiente

| Ferramenta | Versão |
|---|---|
| Windows 11 + Git Bash | |
| Docker Desktop | 29.9.1 |
| AWS SAM CLI | 1.174.0 |
| Java (máquina e runtime `java21` do Lambda) | 21 |
| Maven (o mesmo do `./mvnw` do projeto) | 3.9.9 |

Evidência: [`evidencias/00-versoes.txt`](evidencias/00-versoes.txt).

## Como rodar

Pré-requisitos: Docker rodando, SAM CLI e JDK 21. Use o Git Bash, a partir desta pasta (`serverless/guia-transporte`).

```bash
# 0. prepara o terminal (Maven do wrapper no PATH, sam e o caminho da API principal)
source ./ambiente.sh

# 1. build (o SAM roda o Maven, que compila o handler + o domínio da Expedição)
sam build

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

# testes unitários da função (não precisa do Docker)
cd GuiaTransporteFunction && mvn test
```

Exemplo de pedido e de resposta:

```bash
curl -X POST http://127.0.0.1:3000/guias -H "Content-Type: application/json" \
  -d '{"requisicaoId":"R-002","hospital":"H-Caruaru","quantidade":2}'
```

```json
{"guia":"G-R-002","requisicaoId":"R-002","hospital":"H-Caruaru","fornecedorId":"HEMOPE","insumoId":"O-NEG",
 "quantidade":2,"bolsas":[],"coletaInicio":"2026-11-24T19:51:07.093294746Z","coletaFim":"2026-11-24T21:51:07.093294746Z",
 "estado":"PREPARADO","invocacaoNesteAmbiente":1}
```

Campos opcionais: `hospital`, `fornecedorId` (padrão `HEMOPE`), `insumoId` (padrão `O-NEG`), `quantidade` (padrão 1), `bolsas` e `demorar`.

## O que fiz e o que observei

### Passo 2: projeto

Rodei `sam init` com *Hello World Example*, `java21`, Maven e pacote `zip`. Troquei a rota `/hello` (GET) por **`/guias` (POST)** e o `HelloWorld` pelo `GuiaTransporteHandler`, que faz o mesmo que o código do enunciado: contador global, `requisicaoId` obrigatório (400), `demorar` e resposta 201 com `invocacaoNesteAmbiente`. A diferença é que a guia é montada pelo domínio da Expedição.

Problemas que encontrei no caminho:

1. **Body em base64.** O `sam local generate-event` (SAM CLI 1.174) grava o body em base64, com `"isBase64Encoded": true`. O handler decodifica antes de ler o JSON (método `lerBody`). Existe um teste para esse caso.
2. **`sam build` e o código da API.** O SAM **copia** a pasta da função para um diretório temporário antes de rodar o Maven. O caminho relativo `../../../src/main/java` deixa de existir lá, e o build falhava com `JavaMavenWorkflow:MavenBuild - Maven Failed`. Resolvi com um profile no `pom.xml`, que usa o caminho absoluto da variável `ROTA_VITAL_RAIZ`, exportada pelo `ambiente.sh`.
3. **`mvn` não encontrado.** Para `java21`, o SAM chama `mvn`, e eu só tinha o `./mvnw` do projeto. O `ambiente.sh` coloca no PATH o Maven que o próprio wrapper já baixou.

### Passo 3: invocação com evento

**Primeiro achado: com Timeout de 3 s, a função Java nem responde.** Deixei `Timeout: 3`, como no enunciado, e as duas invocações deram `Function 'GuiaTransporteFunction' timed out after 3 seconds` ([`02-0-invoke-timeout-3s-cold-start-java.txt`](evidencias/02-0-invoke-timeout-3s-cold-start-java.txt)). O cold start da JVM (subir o runtime, carregar as classes e inicializar o Jackson) já passa de 3 s. É por isso que o template Java do `sam init` vem com `Timeout: 20`, enquanto o de Python vem com 3. Passei o Timeout para 10 s.

Evidência: [`evidencias/02-invoke-evento.txt`](evidencias/02-invoke-evento.txt)

| Invocação | Tempo total (relógio) | `Duration` no REPORT | Resposta |
|---|---|---|---|
| 1ª | 19,4 s | 2.854,75 ms | 201, `invocacaoNesteAmbiente: 1` |
| 2ª | 17,1 s | 2.425,28 ms | 201, `invocacaoNesteAmbiente: 1` |

A primeira invocação de todas, quando o Docker ainda montou a imagem do runtime `java21`, levou 56 s. O `sam local invoke` **cria um container novo a cada execução**, então as duas medições acima são cold start. Quase 3 s de `Duration` é só a JVM "esquentando". A diferença entre frio e quente aparece no Passo 4.

O `Init Duration` do emulador local aparece como ~0,1 ms. O emulador não mede o Init como o Lambda real; no emulador, o custo da JVM fria cai dentro do `Duration`.

### Passo 4: HTTP com `start-api`

O `samconfig.toml` gerado pelo `sam init` já vem com `warm_containers = "EAGER"` para o `start-api`. Comentei essas linhas para que o teste "sem warm containers" fosse de verdade.

| Modo | Chamada 1 | Chamada 2 | Chamada 3 | `Duration` (REPORT) | `invocacaoNesteAmbiente` |
|---|---|---|---|---|---|
| Sem warm ([04a](evidencias/04a-curl-sem-warm.txt), [log](evidencias/04a-servidor-sem-warm.txt)) | 6,99 s | 5,24 s | 6,24 s | 2.370 / 2.584 / 3.590 ms | **1, 1, 1** |
| `--warm-containers LAZY` ([04b](evidencias/04b-curl-warm-lazy.txt), [log](evidencias/04b-servidor-warm-lazy.txt)) | 8,02 s | 1,10 s | 1,06 s | 3.533 / **5,66 / 3,62** ms | **1, 2, 3** |

* **Sem warm:** cada requisição ganhou um container e uma JVM nova. O `contador` sempre recomeçou do zero, e toda chamada pagou ~2,5–3,6 s de JVM fria.
* **LAZY:** o container é criado na 1ª requisição e reaproveitado depois. O `contador` sobreviveu (1, 2, 3), e a função quente respondeu em **3 a 6 ms**, centenas de vezes mais rápido que a fria. Em Java a diferença entre cold e warm é muito maior do que seria em Python.
* Validações (no mesmo arquivo 04b): sem `requisicaoId` → **400**. Com `quantidade: 0` → **422**, recusado pelo `PrepararTransferencia`.

### Passo 5: limite de tempo

Como o Timeout base passou a ser 10 s, adaptei o experimento: com `demorar: true` a função dorme **12 s**, mais que o limite. Depois subi o Timeout para **20 s**, que é o padrão do template Java.

| Timeout no template | Resultado | Evidência |
|---|---|---|
| 10 s | **HTTP 502** `{"message":"Internal server error"}`. Log: `Function 'GuiaTransporteFunction' timed out after 10 seconds` | [05a](evidencias/05a-timeout-10s.txt), [log](evidencias/05a-servidor-timeout-10s.txt) |
| 20 s (após `sam build`) | **HTTP 201**, guia `G-R-003`. REPORT: `Duration: 13950.17 ms` (≈ 2 s de JVM fria + 12 s dormindo) | [05b](evidencias/05b-timeout-20s.txt), [log](evidencias/05b-servidor-timeout-20s.txt) |

## Respostas

**1. Por que a primeira invocação demorou mais que a segunda? O que acontece na fase de Init?**

Na primeira invocação não existe ambiente pronto, então a plataforma precisa criar um do zero (*cold start*). Na fase de **Init**, o Lambda: (1) cria o ambiente isolado (na AWS é uma microVM Firecracker; aqui é um container Docker); (2) baixa e monta o código (o jar/zip com as classes e bibliotecas); (3) inicia o runtime, que no Java é **subir a JVM**; (4) roda a inicialização estática da classe do handler, que aqui cria o `ObjectMapper` do Jackson, o repositório em memória e o `PrepararTransferencia`. Só depois vem o **Invoke**, que chama o `handleRequest`. Se o ambiente fica quente, a próxima chamada pula tudo isso.

Em Java isso fica bem visível. No meu teste com LAZY, a 1ª chamada levou 3,5 s de `Duration` e as seguintes de 3 a 6 ms, porque a JVM ainda precisa carregar e compilar (JIT) as classes na primeira vez. Com Timeout de 3 s, a função nem conseguia terminar o cold start. Já no `sam local invoke` as duas medições deram parecido (2,9 s e 2,4 s), porque esse comando sempre cria um container novo: as duas foram frias. Na AWS de verdade dá para reduzir isso com SnapStart (para Java), concorrência provisionada ou deixando o pacote menor.

**2. O que o campo `invocacaoNesteAmbiente` mostrou com e sem `--warm-containers`? O que isso diz sobre guardar estado numa variável global?**

Sem warm containers foi sempre **1**: cada requisição caiu num ambiente novo, e o `static int contador` foi recriado com 0. Com `--warm-containers LAZY` foi **1, 2, 3**, porque o mesmo ambiente atendeu as três chamadas e o campo estático sobreviveu.

Conclusão: variável global (campo `static`) **não guarda estado de forma confiável**. Ela só vive enquanto a plataforma resolve manter aquele ambiente, e em produção várias cópias rodam em paralelo, cada uma com o seu contador. No meu handler isso vale também para o repositório de planos em memória. O `PrepararTransferencia` é idempotente por `tentativaId` (a ADR 0002 do projeto pede isso para comandos repetidos), mas essa idempotência só funciona se a repetição cair no mesmo ambiente. Para valer de verdade, o plano teria que ser gravado fora da função (no PostgreSQL do projeto, no DynamoDB etc.). A global serve para **cache** do que é caro criar e pode ser refeito, como o `ObjectMapper` e as conexões.

**3. O que aconteceu com `demorar = true`? Qual é o tempo máximo de uma execução no Lambda, e o que fazer com um trabalho mais longo?**

Com Timeout de 10 s e a função dormindo 12 s, a execução foi interrompida (`timed out after 10 seconds`), o cliente recebeu **HTTP 502** e a guia não foi gerada. Com Timeout de 20 s, a mesma chamada respondeu 201 em ~14 s. (Com o Timeout de 3 s do enunciado, até a chamada normal estourava, por causa do cold start do Java.)

O máximo no Lambda é **900 segundos (15 minutos)**. Para um trabalho maior que isso, ou que não precisa segurar o cliente esperando, dá para: quebrar em partes menores e processar de forma assíncrona com fila (SQS), em que cada mensagem é uma execução curta; orquestrar as etapas com **Step Functions** (encaixa com a saga da ADR 0002: reservar estoque, preparar expedição e compensar em caso de falha); ou rodar num container sem esse limite (**ECS/Fargate**, **AWS Batch**), como a API Spring do projeto, que já roda em container. Também ajuda responder logo (202 Accepted) e avisar quando terminar.

**4. Quanto custa um milhão de execuções com 128 MB e 100 ms (us-east-1)?**

```
Requisições:  1.000.000 × (US$ 0,20 / 1.000.000)          = US$ 0,20

Computação:   128 MB = 128 / 1024 GB                       = 0,125 GB
              0,125 GB × 0,1 s                             = 0,0125 GB-s por execução
              0,0125 GB-s × 1.000.000 execuções            = 12.500 GB-s
              12.500 GB-s × US$ 0,0000166667               = US$ 0,2083

Total:        US$ 0,20 + US$ 0,2083                        ≈ US$ 0,41
```

Um milhão de guias custaria cerca de **41 centavos de dólar**, e ainda caberia na faixa gratuita (1 milhão de requisições e 400 mil GB-s por mês).

Comparando com a minha função em Java (512 MB): quente ela leva ~5 ms, ou seja, 0,5 GB × 0,005 s × 1.000.000 = 2.500 GB-s ≈ US$ 0,04, mais US$ 0,20 de requisições ≈ **US$ 0,24**. Mas cada cold start custa ~3 s × 0,5 GB = 1,5 GB-s. Se 1% das chamadas fossem frias, seriam mais 15.000 GB-s ≈ US$ 0,25. Em Java, o cold start pesa no custo além de pesar no tempo de resposta.

**5. Em que parte do Rota Vital uma função assim faria sentido, e em qual não faria?**

*Faz sentido* em tarefas curtas, sem estado e disparadas por evento, com tráfego irregular. A principal é justamente a desta atividade: quando a **Alocação confirma** a requisição (`ConfirmarAlocacao` chama o `PreparoDeExpedicao`), gerar a guia de transporte / plano de transferência. Outras: avisar o hospital e o fornecedor (e-mail, SMS) com a janela de coleta, gerar o PDF/QR code da guia e registrar a entrega. Uma emergência pode gerar várias requisições de uma vez e depois ficar horas sem nada. Escalar sozinho e pagar por execução encaixa bem.

*Não faz sentido* no **motor de cruzamento**, que é o coração do PI de threads. Ele compara sequencial, threads de plataforma e threads virtuais sobre snapshots grandes **em memória**, e depende de uma JVM quente e de vida longa para os benchmarks serem justos. Cold start, limite de memória e limite de 15 minutos distorceriam as medições, e o "cache" em memória sumiria quando o ambiente fosse reciclado. Também não faz sentido na **reserva de estoque**, que precisa de consistência e da saga com compensação da ADR 0002, nem na API principal com pool de conexões no PostgreSQL e tráfego contínuo. Essas ficam melhor no container Spring que o projeto já tem (Docker Compose / Render).

## Testes

`cd GuiaTransporteFunction && mvn test`: 6 testes passando. Eles cobrem: a guia com a janela de 2 h do domínio, o body em base64, o 400 sem `requisicaoId`, o 422 com quantidade inválida, o contador no mesmo ambiente e o próprio `events/pedido.json`. O `ArquiteturaEmCamadasTest` do projeto principal continua passando. Evidência: [`evidencias/06-testes-unitarios.txt`](evidencias/06-testes-unitarios.txt).

## Evidências

Os `.txt` em `evidencias/` são a saída real dos comandos, capturada no Git Bash. Os PNG em `evidencias/prints/` foram gerados a partir deles com `scripts/gerar-prints.py`, só para facilitar a leitura.

| Arquivo | O que mostra |
|---|---|
| `00-versoes` | SAM CLI, Maven, Docker e Java |
| `01-sam-build` | `sam build` (Maven compilando o handler + o domínio da Expedição) |
| `02-0-invoke-timeout-3s-cold-start-java` | com Timeout 3 s, o cold start do Java estoura o limite |
| `02-invoke-evento` | duas invocações com evento e tempos (Timeout 10 s) |
| `04a-*` | `start-api` sem warm containers: 1, 1, 1 |
| `04b-*` | `start-api --warm-containers LAZY`: 1, 2, 3; 400 e 422 |
| `05a-*` | timeout de 10 s: 502 e `timed out after 10 seconds` |
| `05b-*` | timeout de 20 s: 201 em ~14 s |
| `06-testes-unitarios` | testes da função |

Vídeo (até 3 min): _link do vídeo aqui_

## Uso de IA

Declaração conforme a PO-CESAR-021.

* **Ferramenta:** Claude (Anthropic), via Claude Code.
* **Finalidade:** apoio para montar o projeto SAM em Java em cima do domínio de Expedição do Rota Vital, rodar os comandos dos experimentos, investigar os problemas (body em base64, `sam build` copiando o código para outra pasta, `mvn` fora do PATH, o EAGER que já vem no `samconfig.toml` e o cold start do Java estourando 3 s), escrever os testes e o script dos prints, e revisar a redação do README e a conta de custo.
* **Como validei:** rodei todos os comandos e conferi as saídas guardadas em `evidencias/`. Confirmei que o jar contém as classes `br.com.rotavital.expedicao.negocio.*` da API, que o `ArquiteturaEmCamadasTest` continua passando e que a janela de coleta da guia bate com a regra do `PrepararTransferencia` (teste `geraGuiaComJanelaDeColetaDoDominioDeExpedicao`). Refiz a conta de custo à mão e conferi o limite de 15 minutos e os preços na documentação da AWS.
