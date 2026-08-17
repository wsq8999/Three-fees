@{
    SchemaVersion = 1

    Paths = @{
        DeploymentRoot = 'C:\ProgramData\ThreeFees'
        FileRoot = 'C:\ProgramData\ThreeFees\shared\files'
        LogRoot = 'C:\ProgramData\ThreeFees\shared\logs'
        BackupRoot = 'D:\ThreeFeesBackups'
        RestoreDrillRoot = 'D:\ThreeFeesRestoreDrills'
    }

    Services = @{
        Api = @{
            Name = 'three-fees-api'
            ExpectedState = 'Running'
            ExpectedStartMode = 'Auto'
            HealthUri = 'http://127.0.0.1:8080/actuator/health'
            ListenPort = 8080
        }
        Worker = @{
            Name = 'three-fees-worker'
            ExpectedState = 'Stopped'
            ExpectedStartMode = 'Manual'
            ListenPort = 0
        }
    }

    Database = @{
        Host = '127.0.0.1'
        Port = 3306
        Name = 'three_fees'
        DumpExecutable = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqldump.exe'
        ClientExecutable = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe'
        BackupUsernameEnvironmentVariable = 'THREE_FEES_BACKUP_DB_USERNAME'
        BackupPasswordEnvironmentVariable = 'THREE_FEES_BACKUP_DB_PASSWORD'
        RestoreUsernameEnvironmentVariable = 'THREE_FEES_RESTORE_DB_USERNAME'
        RestorePasswordEnvironmentVariable = 'THREE_FEES_RESTORE_DB_PASSWORD'
    }

    Backup = @{
        DefaultConsistencyMode = 'Quiesced'
        AppendOnlyFileContractApproved = $false
        QuiesceMarkerMaxWindowMinutes = 120
        DailyRetentionCount = 7
        WeeklyRetentionCount = 4
    }

    Restore = @{
        DatabaseNamePrefix = 'three_fees_restore_drill_'
        AllowedDatabaseHosts = @('127.0.0.1', 'localhost', '::1')
        IsolationAcknowledgement = 'ISOLATED-RESTORE-ONLY'
        MaximumDrillAgeDays = 90
    }

    Thresholds = @{
        RpoHours = 24
        RtoHours = 4
        BackupWarningHours = 20
        MinimumFreeGigabytes = 20
        MinimumFreePercent = 15
        MaximumLogGigabytes = 10
        MaximumRequiredLogAgeMinutes = 30
        RecentLogWindowHours = 24
        MaximumRecentErrorCount = 20
    }
}
