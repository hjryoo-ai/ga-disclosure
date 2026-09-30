# WeasyPrint 후보(비교용 사이드카). 3B 선결정은 openhtmltopdf — 전환 조건이 생기면(§6.4 9항) 이 이미지가 출발점이다.
FROM python:3.13-slim
RUN apt-get update && apt-get install -y --no-install-recommends libpango-1.0-0 libpangoft2-1.0-0 libharfbuzz-subset0 \
 && rm -rf /var/lib/apt/lists/* && pip install --no-cache-dir weasyprint==70.0
WORKDIR /w
