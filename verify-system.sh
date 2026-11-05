#!/bin/bash
# 系统验证脚本

echo "=========================================="
echo "智岗图谱系统 - 大模型集成验证"
echo "=========================================="
echo ""

echo "1. 检查 Docker 容器状态..."
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep zhigang

echo ""
echo "2. 检查后端健康状态..."
curl -s http://127.0.0.1:8080/actuator/health | grep -o '"status":"[^"]*"' | head -5

echo ""
echo "3. 检查前端服务..."
curl -s http://127.0.0.1:5173 > /dev/null && echo "✓ 前端运行中 (http://127.0.0.1:5173)" || echo "✗ 前端未运行"

echo ""
echo "4. 测试大模型推理（英文）..."
RESULT=$(echo '{"query":"test"}' | curl -s -X POST "http://127.0.0.1:8080/api/emerging-discovery/queries" \
  -H "Content-Type: application/json" --data-binary @-)

if echo "$RESULT" | grep -q '"code":"OK"'; then
    echo "✓ 大模型推理成功"
    echo "$RESULT" | grep -o '"name":"[^"]*"' | head -1
    echo "$RESULT" | grep -o '"modelName":"[^"]*"'
    echo "$RESULT" | grep -o '"evidenceCount":[0-9]*'
else
    echo "✗ 大模型推理失败"
    echo "$RESULT" | grep -o '"code":"[^"]*"'
fi

echo ""
echo "=========================================="
echo "验证完成！"
echo ""
echo "访问地址："
echo "  前端界面: http://127.0.0.1:5173"
echo "  发现新岗位: http://127.0.0.1:5173/discovery"
echo "  后端 API: http://127.0.0.1:8080/swagger-ui.html"
echo "=========================================="
