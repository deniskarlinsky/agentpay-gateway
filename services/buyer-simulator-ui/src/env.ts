/**
 * Typed environment helper.
 *
 * Vite exposes any variable prefixed with `VITE_` on `import.meta.env`. We
 * centralise the lookup here so callers depend on a typed `env` object rather
 * than reaching into `import.meta.env` directly — that keeps default values
 * and naming in one place.
 *
 * Defaults match the gateway URL used by the Java buyer-client
 * (`services/buyer-client/src/main/resources/application.yml`), so a local
 * `make up` + `npm run dev` flow needs zero environment configuration.
 */
export type Env = Readonly<{
  /**
   * Base URL of the AgentPay gateway. Used as the prefix for every API call
   * the simulator makes (intent token, payment submission, case status). No
   * trailing slash.
   */
  gatewayBaseUrl: string
}>

const rawGatewayBaseUrl =
  import.meta.env.VITE_GATEWAY_BASE_URL ?? 'http://localhost:8080'

export const env: Env = {
  gatewayBaseUrl: rawGatewayBaseUrl.replace(/\/$/, ''),
}
