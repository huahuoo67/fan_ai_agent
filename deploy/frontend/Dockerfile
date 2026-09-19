FROM nginx:alpine
COPY dist/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY .htpasswd /etc/nginx/.htpasswd
EXPOSE 80
ENTRYPOINT ["nginx", "-g", "daemon off;"]
