# Despliegue en AWS (Fargate)

Por que Fargate y no EC2: el entorno de laboratorio tiene una **denegacion
explicita** sobre `ec2:CreateFleet` en `VocLabPolicy2`, aplicada al `LabRole`.
Esa denegacion no se puede quitar —es la barrera del laboratorio, no una
configuracion— y una denegacion explicita en IAM gana siempre. El capacity
provider de instancias administradas llama a esa API para levantar instancias,
asi que cualquier cluster basado en EC2 administrado va a fallar con:

```
ResourceInitializationError: Unable to launch instance(s) for capacity provider
... is not authorized to perform: ec2:CreateFleet ... explicit deny
```

Fargate no aprovisiona instancias, asi que nunca llama a `CreateFleet`.

## Antes de la tarea

**1. La base.** Una RDS PostgreSQL 16, `db.t3.micro`, con el security group
aceptando el 5432 **desde el security group de la tarea**, no desde `0.0.0.0/0`.
No hace falta crear el esquema: Flyway lo crea en el primer arranque.

**2. La imagen en ECR.** Construida para `linux/amd64` y sin attestations, que
generan un indice multiplataforma que ECS a veces no sabe resolver:

```
docker buildx build --platform linux/amd64 --provenance=false -t checkout-app .
```

## La tarea

`checkout-task-fargate.json` esta lista salvo los valores marcados `RELLENAR`.

Registrarla:

```
aws ecs register-task-definition --cli-input-json file://deploy/checkout-task-fargate.json
```

### Decisiones que lleva dentro, y por que

**512 CPU / 1024 MB, no 256/512.** La JVM toma por defecto el 25% de la memoria
del contenedor. Con 512 MB eso deja ~128 MB de heap para Spring Boot con Tomcat,
Hibernate, HikariCP y Flyway: arranca lentisimo o se queda sin memoria, y el
health check lo mata antes de que termine.

**`SPRING_PROFILES_ACTIVE` vacio.** Con `dev` se publica Swagger y cada consulta
SQL acaba en el log. El perfil por defecto es el seguro.

**Ocho variables obligatorias.** `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`,
`DB_PASSWORD`, `JWT_SECRET`, `JWT_EXPIRATION_ACCESS` y `JWT_EXPIRATION_REFRESH`
no tienen valor por defecto: si falta una, el contenedor no arranca y el log
dice cual. Las dos de expiracion se olvidan facil porque el compose local les
pone default y la aplicacion no.

**Dos variables opcionales: `ALPACA_API_KEY` y `ALPACA_API_SECRET`.** Sin
ellas el contenedor arranca igual, pero el catalogo de acciones, los precios y
el historico de Alpaca responden 503. Con ellas, el primer arranque descarga el
catalogo (unas 13,5 mil acciones, ~10 s) despues de quedar listo, asi que no
afecta al health check. Son secretas: no van al frontend ni a git.

**El health check no exige un 200.** No hay actuator en el proyecto, asi que no
existe `/actuator/health`, y todas las rutas responden 401 o 404 sin token. Lo
que comprueba es que el servidor **contesta HTTP**, que es lo que significa que
la aplicacion esta viva. Usa `wget` porque la imagen alpine no trae `curl`.

**`startPeriod: 120`.** La aplicacion tarda ~13 s en local con recursos de
sobra; en Fargate, con Flyway migrando en el primer arranque, necesita mas.
Sin esta ventana ECS la mata antes de que llegue a escuchar.

**`awslogs-create-group: true`.** Sin esto hay que crear el grupo a mano antes,
y el sintoma de no hacerlo es una tarea que muere sin dejar ni una linea de log.

## Al crear el servicio

- Subred **publica** con **Assign public IP = ENABLED**. Sin salida a internet
  la tarea no puede bajar la imagen de ECR y falla con otro
  `ResourceInitializationError`, parecido al de EC2 pero por otra causa.
- Security group de la tarea: entrada 8080 desde donde vaya a consumirse.
- El cluster debe ser **solo Fargate**. Un cluster creado con capacity provider
  de instancias administradas arrastra el defecto y no se corrige editandolo:
  se crea uno nuevo.

## Pendiente

**Los secretos van en texto plano en la task definition.** `DB_PASSWORD`,
`JWT_SECRET` y las claves de Alpaca como `environment` los ve cualquiera que pueda
leer la definicion.
Lo correcto es `secrets` con `valueFrom` apuntando a SSM Parameter Store o
Secrets Manager. Se deja asi para el primer despliegue funcionando, no como
estado final.
