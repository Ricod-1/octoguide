# 前端镜像：先用 Node 构建静态资源，再交给 Nginx 提供访问与 /api 反向代理。
# 目标平台为 linux/amd64，与后端、MySQL 保持一致。

# ---------- 构建阶段 ----------
FROM node:20-alpine AS build

WORKDIR /app

# 先装依赖再拷源码，源码变动时依赖层仍可复用缓存。
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---------- 运行阶段 ----------
FROM nginx:1.27-alpine

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 站点配置：SPA history 回退 + /api 反代到后端服务。
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
