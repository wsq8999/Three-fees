@{
    # Names and non-secret examples only. Never put secret values in this tracked file.
    Common = @{
        THREE_FEES_HOME       = 'C:\ProgramData\ThreeFees'
        DB_URL                = 'jdbc:mysql://127.0.0.1:3306/three_fees?characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=DISABLED'
        DB_USERNAME           = ''
        DB_PASSWORD           = ''
        APP_FILE_ROOT         = 'C:\ProgramData\ThreeFees\shared\files'
        REPORT_FONT_PATH      = 'C:\Windows\Fonts\simhei.ttf'
        AI_SERVICE_BASE_URL   = 'http://127.0.0.1:8100'
        AI_SERVICE_TOKEN      = ''
    }

    Api = @{
        SPRING_PROFILES_ACTIVE   = 'prod'
        SERVER_ADDRESS           = '127.0.0.1'
        SERVER_PORT              = '8080'
        SESSION_COOKIE_SECURE    = 'true'
        INITIAL_ACCOUNT_BOOTSTRAP_ENABLED = 'true'
        INITIAL_ACCOUNT_PASSWORD = ''
        THREE_FEES_PROCESS_ROLE  = 'api'
    }

    Worker = @{
        SPRING_PROFILES_ACTIVE        = 'prod'
        SPRING_MAIN_WEB_APPLICATION_TYPE = 'none'
        INITIAL_ACCOUNT_BOOTSTRAP_ENABLED = 'false'
        THREE_FEES_PROCESS_ROLE       = 'worker'
    }

    Ai = @{
        AI_BIND_HOST       = '127.0.0.1'
        AI_BIND_PORT       = '8100'
        AI_SERVICE_TOKEN   = ''
        AI_MODEL_PROVIDER  = 'fake'
        KIMI_API_KEY       = ''
        KIMI_BASE_URL      = 'https://api.moonshot.cn/v1'
        KIMI_MODEL         = ''
    }
}
