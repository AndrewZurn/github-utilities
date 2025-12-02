import os
import sys
import requests
from datetime import datetime

GITHUB_API_URL = "https://api.github.com/graphql"


def run_graphql_query(token: str, query: str, variables: dict) -> dict:
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }
    response = requests.post(
        GITHUB_API_URL,
        json={"query": query, "variables": variables},
        headers=headers,
        timeout=30,
    )
    response.raise_for_status()
    data = response.json()
    if "errors" in data:
        raise RuntimeError(f"GraphQL errors: {data['errors']}")
    return data["data"]


def get_contribution_totals(token: str, username: str, year: int) -> dict:
    query = """
    query ContributionsView($username: String!, $from: DateTime!, $to: DateTime!) {
      user(login: $username) {
        contributionsCollection(from: $from, to: $to) {
          totalCommitContributions
          totalIssueContributions
          totalPullRequestContributions
          totalPullRequestReviewContributions
        }
      }
    }
    """
    from_dt = f"{year}-01-01T00:00:00Z"
    to_dt = f"{year}-12-31T23:59:59Z"

    data = run_graphql_query(
        token,
        query,
        {"username": username, "from": from_dt, "to": to_dt},
    )
    collection = data["user"]["contributionsCollection"]
    return {
        "total_commits": collection["totalCommitContributions"],
        "total_prs_contributed": collection["totalPullRequestContributions"],
        "total_prs_reviewed": collection["totalPullRequestReviewContributions"],
    }


def get_total_review_comments(token: str, username: str, year: int) -> int:
    query = """
    query ReviewCommentsCount(
      $username: String!,
      $from: DateTime!,
      $to: DateTime!,
      $after: String
    ) {
      user(login: $username) {
        contributionsCollection(from: $from, to: $to) {
          pullRequestReviewContributions(first: 100, after: $after) {
            pageInfo {
              hasNextPage
              endCursor
            }
            nodes {
              pullRequestReview {
                comments {
                  totalCount
                }
              }
            }
          }
        }
      }
    }
    """
    from_dt = f"{year}-01-01T00:00:00Z"
    to_dt = f"{year}-12-31T23:59:59Z"

    total_comments = 0
    after_cursor = None

    while True:
        variables = {
            "username": username,
            "from": from_dt,
            "to": to_dt,
            "after": after_cursor,
        }
        data = run_graphql_query(token, query, variables)

        contribs = data["user"]["contributionsCollection"][
            "pullRequestReviewContributions"
        ]
        for node in contribs["nodes"]:
            review = node.get("pullRequestReview")
            if review and review.get("comments"):
                total_comments += review["comments"]["totalCount"]

        page_info = contribs["pageInfo"]
        if not page_info["hasNextPage"]:
            break
        after_cursor = page_info["endCursor"]

    return total_comments


def main() -> None:
    if len(sys.argv) < 3:
        print("Usage: python github_contrib_stats.py <github_username> <year>")
        sys.exit(1)

    username = sys.argv[1]
    year = int(sys.argv[2])

    token = os.getenv("GITHUB_TOKEN")
    if not token:
        print("Error: please set the GITHUB_TOKEN environment variable.")
        sys.exit(1)

    try:
        totals = get_contribution_totals(token, username, year)
        total_review_comments = get_total_review_comments(token, username, year)
    except Exception as exc:
        print(f"Error while querying GitHub: {exc}")
        sys.exit(1)

    print(f"GitHub contribution metrics for {username} in {year}:")
    print(f"PRs Reviewed:           {totals['total_prs_reviewed']}")
    print(f"PR Comments Provided:   {total_review_comments}")
    print(f"PRs Contributed:        {totals['total_prs_contributed']}")
    print(f"Total Commits Pushed:   {totals['total_commits']}")


if __name__ == "__main__":
    main()
