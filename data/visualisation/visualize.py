import json
import pandas as pd
import matplotlib.pyplot as plt

with open('comparisons.json', 'r') as f:
    data = json.load(f)

df = pd.json_normalize(data, sep='_')
df['is_busybox'] = df['fileName'].str.contains('busybox')

metrics = [
    'comparisonDetails_CODE_REGION_LIST',
    'comparisonDetails_STRING_MINHASH',
    'comparisonDetails_REGION_COUNT_SIM',
    'comparisonDetails_AVG_REGION_LENGTH_SIM',
    'comparisonDetails_PROGRAM_HEADER_VECTOR'
]

plt.style.use('fivethirtyeight')

# Histogram of similarity scores
plt.figure(figsize=(10, 6))
plt.hist(df[df['is_busybox']]['similarityScore'], bins=25, alpha=0.8, label='busybox', edgecolor='black')
plt.hist(df[~df['is_busybox']]['similarityScore'], bins=25, alpha=0.6, label='others', edgecolor='black')
plt.xlabel('Similarity Score', fontsize=14)
plt.ylabel('Frequency', fontsize=14)
plt.title('Distribution of Similarity Scores', fontsize=16, weight='bold')
plt.legend(fontsize=12)
plt.tight_layout()
plt.show()

for metric in metrics:
    label = metric.replace('comparisonDetails_', '').replace('_', ' ').title()

    x_busy  = df[df['is_busybox']][metric]
    y_busy  = df[df['is_busybox']]['similarityScore']
    x_other = df[~df['is_busybox']][metric]
    y_other = df[~df['is_busybox']]['similarityScore']

    plt.figure(figsize=(10, 6))
    plt.scatter(x_busy,  y_busy,  marker='o', s=80, alpha=0.7,
                label='busybox', edgecolors='black')
    plt.scatter(x_other, y_other, marker='^', s=80, alpha=0.7,
                label='others',  edgecolors='black')
    plt.axhline(0.8, linestyle='--', linewidth=2, color='black')
    plt.xlabel(f'{label} Similarity', fontsize=14)
    plt.ylabel('Similarity Score',    fontsize=14)
    plt.title(f'Similarity Score vs {label}', fontsize=16, weight='bold')
    plt.legend(fontsize=12)
    plt.tight_layout()
    plt.show()

# average metric values by group
group_means = df.groupby('is_busybox')[metrics].mean().T
x = range(len(metrics))

labels = [m.replace('comparisonDetails_', '') for m in metrics]

plt.figure(figsize=(12, 7))
plt.bar([i - 0.2 for i in x], group_means[True], width=0.4, alpha=0.8, edgecolor='black', label='busybox')
plt.bar([i + 0.2 for i in x], group_means[False], width=0.4, alpha=0.6, edgecolor='black', label='others')
plt.xticks(x, labels, rotation=45, fontsize=12)
plt.ylabel('Average Similarity', fontsize=14)
plt.title('Average ComparisonDetails by Group', fontsize=16, weight='bold')
plt.legend(fontsize=12)
plt.tight_layout()
plt.show()
