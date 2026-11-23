import base64, json, time

contador = 0  # variável global: só sobrevive se o ambiente for reaproveitado

def ler_body(event):
    """Lê o body do evento do API Gateway.

    O `sam local generate-event` (SAM CLI 1.174) gera o body em base64 com
    "isBase64Encoded": true; o API Gateway real também pode fazer isso.
    Por isso decodifico antes do json.loads.
    """
    body = event.get("body") or "{}"
    if event.get("isBase64Encoded"):
        body = base64.b64decode(body).decode("utf-8")
    return json.loads(body)

def lambda_handler(event, context):
    global contador
    contador += 1
    pedido = ler_body(event)
    if "requisicaoId" not in pedido:
        return {"statusCode": 400,
                "body": json.dumps({"erro": "requisicaoId obrigatório"})}
    if pedido.get("demorar"):
        time.sleep(5)  # passa do Timeout de 3 s do template
    guia = {"guia": "G-" + pedido["requisicaoId"],
            "hospital": pedido.get("hospital"),
            "bolsas": pedido.get("bolsas", []),
            "invocacaoNesteAmbiente": contador}
    return {"statusCode": 201, "body": json.dumps(guia)}
