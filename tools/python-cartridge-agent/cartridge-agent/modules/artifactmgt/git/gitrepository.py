class GitRepository:

    def __init__(self):
        self.repo_url = None
        self.local_repo_path = None
        self.cloned = False
        self.repo = None
        self.tenant_id = None
        self.key_based_auth = False
        self.repo_username = None
        self.repo_password = None
        self.is_multitenant = False
        self.commit_enabled = False
        self.scheduled_update_task = None