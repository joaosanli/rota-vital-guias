"""Testes unitários do handler (só biblioteca padrão: python -m unittest)."""
import base64
import json
import unittest

from hello_world import app


def evento(body, base64_encoded=False):
    texto = json.dumps(body)
    if base64_encoded:
        texto = base64.b64encode(texto.encode()).decode()
    return {"httpMethod": "POST", "path": "/guias",
            "body": texto, "isBase64Encoded": base64_encoded}


class TestHandler(unittest.TestCase):
    def setUp(self):
        app.contador = 0  # simula um ambiente novo (cold start)

    def test_gera_guia_com_body_texto(self):
        r = app.lambda_handler(evento({"requisicaoId": "R-002", "hospital": "H-Caruaru"}), None)
        self.assertEqual(r["statusCode"], 201)
        guia = json.loads(r["body"])
        self.assertEqual(guia["guia"], "G-R-002")
        self.assertEqual(guia["bolsas"], [])

    def test_gera_guia_com_body_base64(self):
        # formato produzido pelo `sam local generate-event` (events/pedido.json)
        r = app.lambda_handler(evento({"requisicaoId": "R-001", "bolsas": ["O-NEG-0142"]}, True), None)
        self.assertEqual(r["statusCode"], 201)
        self.assertEqual(json.loads(r["body"])["bolsas"], ["O-NEG-0142"])

    def test_sem_requisicao_id_retorna_400(self):
        r = app.lambda_handler(evento({"hospital": "H-Recife"}), None)
        self.assertEqual(r["statusCode"], 400)

    def test_contador_cresce_no_mesmo_ambiente(self):
        e = evento({"requisicaoId": "R-002"})
        app.lambda_handler(e, None)
        r = app.lambda_handler(e, None)
        self.assertEqual(json.loads(r["body"])["invocacaoNesteAmbiente"], 2)

    def test_evento_gerado_pelo_sam(self):
        with open("events/pedido.json", encoding="utf-8") as f:
            r = app.lambda_handler(json.load(f), None)
        self.assertEqual(json.loads(r["body"])["guia"], "G-R-001")


if __name__ == "__main__":
    unittest.main()
