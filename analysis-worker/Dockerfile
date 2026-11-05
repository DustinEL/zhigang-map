FROM python:3.11.11-slim
WORKDIR /app
ARG DEBIAN_MIRROR=https://deb.debian.org
RUN sed -i -e "s|http://deb.debian.org|${DEBIAN_MIRROR}|g" \
        -e "s|https://deb.debian.org|${DEBIAN_MIRROR}|g" /etc/apt/sources.list.d/debian.sources \
    && apt-get update && apt-get install -y --no-install-recommends libgl1 libgomp1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
COPY requirements-ocr.txt .
RUN pip install --no-cache-dir -r requirements.txt -r requirements-ocr.txt
RUN sed -i 's/config.switch_ir_optim(True)/config.switch_ir_optim(args.ir_optim)/' \
      /usr/local/lib/python3.11/site-packages/paddleocr/tools/infer/utility.py \
    && grep -q 'config.switch_ir_optim(args.ir_optim)' \
      /usr/local/lib/python3.11/site-packages/paddleocr/tools/infer/utility.py
COPY app.py .
COPY test_app.py .
COPY train_policy.py .
COPY test_train_policy.py .
EXPOSE 8090
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8090"]
